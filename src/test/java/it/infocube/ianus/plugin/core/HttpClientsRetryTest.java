package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.infocube.ianus.plugin.core.util.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Nuovi tentativi verso gli NMS (sezione 8), provati contro un server HTTP locale. */
class HttpClientsRetryTest {

    private final Deque<Integer> statuses = new ArrayDeque<>();
    private final List<String> bodies = new ArrayList<>();
    private final RestClient rest = HttpClients.builder(Duration.ofSeconds(1), Duration.ofMillis(300), false).build();
    private HttpServer server;
    private URI uri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private synchronized int nextStatus() {
        return statuses.isEmpty() ? 200 : statuses.poll();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (this) {
            bodies.add(body);
        }
        int status = nextStatus();
        if (status == 0) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            status = 200;
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private void respond(Integer... codes) {
        statuses.addAll(List.of(codes));
    }

    @Test
    void temporaryErrorIsRetriedUntilSuccess() {
        respond(503, 502);

        assertThat(rest.get().uri(uri).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(bodies).hasSize(3);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        respond(503, 504, 503, 200);

        assertThatThrownBy(() -> rest.get().uri(uri).retrieve().toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(bodies).hasSize(3);
    }

    @Test
    void postIsRepeatedWithSameBody() {
        respond(503);

        rest.post().uri(uri).body("{\"method\":\"history.get\"}").retrieve().toBodilessEntity();

        assertThat(bodies).containsExactly("{\"method\":\"history.get\"}", "{\"method\":\"history.get\"}");
    }

    @Test
    void clientAndServerErrorsAreNotRetried() {
        respond(404);
        assertThatThrownBy(() -> rest.get().uri(uri).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(bodies).hasSize(1);

        bodies.clear();
        respond(500);
        assertThatThrownBy(() -> rest.get().uri(uri).retrieve().toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void readTimeoutIsNotRetried() {
        respond(0);

        assertThatThrownBy(() -> rest.get().uri(uri).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void refusedConnectionIsRetriedWithGrowingWait() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        long start = System.nanoTime();

        assertThatThrownBy(() -> rest.get().uri(URI.create("http://127.0.0.1:" + closedPort + "/"))
                .retrieve().toBodilessEntity()).isInstanceOf(ResourceAccessException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1_200));
    }
}
