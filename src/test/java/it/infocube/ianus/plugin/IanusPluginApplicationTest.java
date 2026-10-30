package it.infocube.ianus.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import it.infocube.ianus.plugin.core.source.SourceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Avvio completo del contesto con tutte le sorgenti abilitate e NMS irraggiungibili:
 * il plugin deve partire, rispondere su HTTP e segnalare le sorgenti come non raggiungibili.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ianus.api-key=test-key",
        "ianus.sources.opennms.enabled=true",
        "ianus.sources.opennms.url=http://127.0.0.1:1/opennms",
        "ianus.sources.prometheus.enabled=true",
        "ianus.sources.prometheus.url=http://127.0.0.1:1",
        "ianus.sources.icinga.enabled=true",
        "ianus.sources.icinga.url=https://127.0.0.1:1",
        "ianus.sources.icinga.poll-interval=PT1H",
        "ianus.sources.zabbix.enabled=true",
        "ianus.sources.zabbix.url=http://127.0.0.1:1/api_jsonrpc.php"
})
class IanusPluginApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    List<SourceAdapter> adapters;

    @Test
    void allSourcesAreRegistered() {
        assertThat(adapters).extracting(SourceAdapter::system)
                .containsExactlyInAnyOrder("opennms", "prometheus", "icinga", "zabbix");
    }

    @Test
    void healthRequiresApiKey() throws Exception {
        assertThat(get("/ianus/v2/health", null).statusCode()).isEqualTo(401);
        assertThat(get("/ianus/v2/health", "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void healthReportsUnreachableSources() throws Exception {
        HttpResponse<String> res = get("/ianus/v2/health", "test-key");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
        assertThat(res.body()).contains("\"status\":\"DEGRADED\"", "\"plugin_version\":\"2.0.0\"",
                "\"reachable\":false");
    }

    @Test
    void unknownSourceIs404AndUnalignedEndIs400() throws Exception {
        HttpResponse<String> notFound = get("/ianus/v2/syslog/metrics?window=PT5M", "test-key");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("\"code\":\"source_not_found\"");

        HttpResponse<String> bad = get("/ianus/v2/zabbix/metrics?window=PT5M&end=2026-10-14T10:03:00Z", "test-key");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("\"code\"", "\"message\"");
    }

    @Test
    void openApiIsPublicAndListsEndpoints() throws Exception {
        HttpResponse<String> res = get("/v3/api-docs", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("/ianus/v2/{source}/metrics", "/ianus/v2/{source}/events",
                "/ianus/v2/{source}/capabilities", "/ianus/v2/health");
    }

    private HttpResponse<String> get(String path, String apiKey) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (apiKey != null) {
            req.header("X-API-Key", apiKey);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
