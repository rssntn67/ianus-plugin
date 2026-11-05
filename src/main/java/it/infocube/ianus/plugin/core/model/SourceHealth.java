package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** lastCollected: fine dell'ultimo blocco raccolto; assente se non c'e' ancora nessun blocco. */
public record SourceHealth(
        @JsonProperty(required = true) String system,
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) boolean reachable,
        String message,
        @JsonProperty("last_collected") @JsonInclude(JsonInclude.Include.NON_NULL) String lastCollected) {

    public SourceHealth(String system, String id, boolean reachable, String message) {
        this(system, id, reachable, message, null);
    }

    public SourceHealth withLastCollected(String instant) {
        return new SourceHealth(system, id, reachable, message, instant);
    }
}
