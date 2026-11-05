package it.infocube.ianus.plugin.sources.prometheus;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.infocube.ianus.plugin.core.util.HttpClients;
import it.infocube.ianus.plugin.core.util.Json;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Client della HTTP API di Prometheus (/api/v1). */
public class PrometheusClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient rest;
    private final String base;

    public PrometheusClient(PrometheusProperties p) {
        this.base = HttpClients.trimSlash(p.url());
        this.rest = HttpClients.builder(p.connectTimeout(), p.readTimeout(), p.insecure())
                .defaultHeaders(h -> {
                    if (p.bearerToken() != null && !p.bearerToken().isBlank()) {
                        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + p.bearerToken());
                    } else if (p.username() != null) {
                        h.setBasicAuth(p.username(), p.password() == null ? "" : p.password());
                    }
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();
    }

    /** Query istantanea: elenco di {metric: {...}, value: [ts, "v"]}. */
    public List<Map<String, Object>> query(String expr, Instant at) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", expr);
        params.put("time", seconds(at));
        return result(get("/api/v1/query", params));
    }

    /** Query su intervallo: elenco di {metric: {...}, values: [[ts, "v"], ...]}. */
    public List<Map<String, Object>> queryRange(String expr, Instant start, Instant end, Duration step) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", expr);
        params.put("start", seconds(start));
        params.put("end", seconds(end));
        params.put("step", String.format(Locale.ROOT, "%.3f", step.toMillis() / 1000.0));
        return result(get("/api/v1/query_range", params));
    }

    public void healthy() {
        rest.get().uri(URI.create(base + "/-/healthy")).retrieve().toBodilessEntity();
    }

    private Map<String, Object> get(String path, Map<String, Object> params) {
        String uri = base + path + (params.isEmpty() ? "" : "?" + HttpClients.query(params));
        Map<String, Object> body = rest.get().uri(URI.create(uri)).retrieve().body(MAP);
        return body == null ? Map.of() : body;
    }

    private static List<Map<String, Object>> result(Map<String, Object> body) {
        return Json.maps(Json.map(body.get("data")).get("result"));
    }

    static String seconds(Instant t) {
        return String.format(Locale.ROOT, "%.3f", t.toEpochMilli() / 1000.0);
    }
}
