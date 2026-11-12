package it.infocube.ianus.plugin.core.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Nuovi tentativi con attesa crescente verso gli NMS (sezione 8): su connessione fallita e su
 * risposte 502, 503, 504. Il timeout di lettura non si ripete, perche' l'attesa e' gia' stata lunga.
 * Tutte le chiamate agli NMS sono letture, quindi si possono ripetere anche le POST.
 */
final class RetryInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RetryInterceptor.class);
    private static final List<Duration> WAITS = List.of(Duration.ofMillis(200), Duration.ofSeconds(1));

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        for (Duration wait : WAITS) {
            try {
                ClientHttpResponse response = execution.execute(request, body);
                if (!HttpClients.TEMPORARY.contains(response.getStatusCode().value())) {
                    return response;
                }
                LOG.debug("{} {}: risposta {}, nuovo tentativo", request.getMethod(), request.getURI(),
                        response.getStatusCode().value());
                response.close();
            } catch (IOException e) {
                if (isReadTimeout(e)) {
                    throw e;
                }
                LOG.debug("{} {}: {}, nuovo tentativo", request.getMethod(), request.getURI(), e.toString());
            }
            sleep(wait);
        }
        return execution.execute(request, body);
    }

    private static boolean isReadTimeout(IOException e) {
        return e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException);
    }

    private static void sleep(Duration wait) throws IOException {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Attesa del nuovo tentativo interrotta");
        }
    }
}
