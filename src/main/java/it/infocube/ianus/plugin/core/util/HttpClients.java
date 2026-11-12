package it.infocube.ianus.plugin.core.util;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Costruzione dei RestClient verso gli NMS (timeout, nuovi tentativi, TLS, HTTP/1.1). */
public final class HttpClients {

    /** Risposte di un NMS temporaneamente indisponibile: si ripetono e, se persistono, l'NMS e' giu'. */
    static final Set<Integer> TEMPORARY = Set.of(502, 503, 504);

    private HttpClients() {
    }

    /** NMS giu': connessione fallita, o risposta 502, 503 o 504 anche dopo i nuovi tentativi. */
    public static boolean unreachable(RuntimeException e) {
        return e instanceof ResourceAccessException
                || e instanceof HttpStatusCodeException h && TEMPORARY.contains(h.getStatusCode().value());
    }

    public static RestClient.Builder builder(Duration connectTimeout, Duration readTimeout, boolean insecure) {
        HttpClient.Builder http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout);
        if (insecure) {
            http.sslContext(trustAll());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).requestInterceptor(new RetryInterceptor());
    }

    public static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Query string con codifica completa dei valori (necessaria per PromQL e FIQL). */
    public static String query(Map<String, ?> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> joiner.add(encode(k) + "=" + encode(String.valueOf(v))));
        return joiner.toString();
    }

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static SSLContext trustAll() {
        TrustManager[] managers = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // certificati client non verificati
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // modalita' insecure: certificato del server non verificato
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Impossibile inizializzare il contesto TLS", e);
        }
    }
}
