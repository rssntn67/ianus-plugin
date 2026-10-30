package it.infocube.ianus.plugin.sources.zabbix;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import it.infocube.ianus.plugin.core.util.HttpClients;
import it.infocube.ianus.plugin.core.util.Json;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Client JSON-RPC 2.0 dell'API di Zabbix. */
public class ZabbixClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** Errore restituito dall'API (campo error della risposta JSON-RPC). */
    public static class ZabbixException extends RuntimeException {
        public ZabbixException(String message) {
            super(message);
        }
    }

    private final RestClient rest;
    private final URI endpoint;
    private final String token;
    private final boolean tokenInHeader;
    private final AtomicLong ids = new AtomicLong();

    public ZabbixClient(ZabbixProperties p) {
        this.endpoint = URI.create(p.url() == null ? "" : p.url().trim());
        this.token = p.token();
        this.tokenInHeader = !"param".equalsIgnoreCase(p.authMode());
        this.rest = HttpClients.builder(p.connectTimeout(), p.readTimeout(), p.insecure()).build();
    }

    /** Chiama un metodo; authenticated = false per apiinfo.version, che rifiuta le credenziali. */
    public Object call(String method, Object params, boolean authenticated) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", ids.incrementAndGet());
        if (authenticated && !tokenInHeader) {
            request.put("auth", token);
        }
        RestClient.RequestBodySpec spec = rest.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON);
        if (authenticated && tokenInHeader) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        Map<String, Object> response = spec.body(request).retrieve().body(MAP);
        if (response == null) {
            throw new ZabbixException(method + ": risposta vuota");
        }
        if (response.get("error") != null) {
            Map<String, Object> error = Json.map(response.get("error"));
            throw new ZabbixException(method + ": " + Json.str(error.get("message"), "errore")
                    + " " + Json.str(error.get("data"), ""));
        }
        return response.get("result");
    }
}
