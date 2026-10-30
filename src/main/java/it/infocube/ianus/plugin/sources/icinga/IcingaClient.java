package it.infocube.ianus.plugin.sources.icinga;

import java.net.URI;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.util.HttpClients;
import it.infocube.ianus.plugin.core.util.Json;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Client dell'API REST di Icinga 2 (porta 5665, percorso /v1, sola lettura). */
public class IcingaClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String HOST_ATTRS =
            "attrs=name&attrs=state&attrs=last_state_change&attrs=last_check_result";
    private static final String SERVICE_ATTRS =
            "attrs=name&attrs=host_name&attrs=state&attrs=last_state_change&attrs=last_check_result";

    private final RestClient rest;
    private final String base;

    public IcingaClient(IcingaProperties p) {
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

    public List<Map<String, Object>> hosts() {
        return results("/v1/objects/hosts?" + HOST_ATTRS);
    }

    public List<Map<String, Object>> services() {
        return results("/v1/objects/services?" + SERVICE_ATTRS);
    }

    public void status() {
        rest.get().uri(URI.create(base + "/v1/status/IcingaApplication")).retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> results(String path) {
        Map<String, Object> body = rest.get().uri(URI.create(base + path)).retrieve().body(MAP);
        return Json.maps(body == null ? null : body.get("results"));
    }
}
