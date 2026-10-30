package it.infocube.ianus.plugin.sources.opennms;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.util.HttpClients;
import it.infocube.ianus.plugin.core.util.Json;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Client REST OpenNMS: nodi, risorse, Measurements API ed eventi (API v2). */
public class OpenNmsClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient rest;
    private final String base;

    public OpenNmsClient(OpenNmsProperties p) {
        this.base = HttpClients.trimSlash(p.url());
        this.rest = HttpClients.builder(p.connectTimeout(), p.readTimeout(), p.insecure())
                .defaultHeaders(h -> {
                    if (p.username() != null) {
                        h.setBasicAuth(p.username(), p.password() == null ? "" : p.password());
                    }
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();
    }

    public List<Map<String, Object>> nodes() {
        Map<String, Object> body = rest.get().uri(URI.create(base + "/rest/nodes?limit=0")).retrieve().body(MAP);
        return Json.maps(body == null ? null : body.get("node"));
    }

    public Map<String, Object> resourcesForNode(String nodeId) {
        Map<String, Object> body = rest.get()
                .uri(URI.create(base + "/rest/resources/fornode/" + HttpClients.encode(nodeId)))
                .retrieve().body(MAP);
        return body == null ? Map.of() : body;
    }

    /**
     * POST /rest/measurements. La risposta puo' contenere NaN non quotati (JSON non valido):
     * si legge come testo, si sostituiscono con null e si interpreta.
     */
    public Map<String, Object> measurements(Map<String, Object> request) {
        String body = rest.post().uri(URI.create(base + "/rest/measurements"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String sanitized = body.replaceAll("(?<![\"\\w])-?(NaN|Infinity)(?![\"\\w])", "null");
        return JsonParserFactory.getJsonParser().parseMap(sanitized);
    }

    /** GET /api/v2/events con filtro FIQL; HTTP 204 (nessun evento) restituisce una mappa vuota. */
    public Map<String, Object> events(String fiql, int offset, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_s", fiql);
        params.put("limit", limit);
        params.put("offset", offset);
        Map<String, Object> body = rest.get()
                .uri(URI.create(base + "/api/v2/events?" + HttpClients.query(params)))
                .retrieve().body(MAP);
        return body == null ? Map.of() : body;
    }

    public void info() {
        rest.get().uri(URI.create(base + "/rest/info")).retrieve().toBodilessEntity();
    }
}
