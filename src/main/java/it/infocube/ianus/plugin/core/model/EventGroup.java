package it.infocube.ianus.plugin.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Gruppo di eventi della finestra (sezione 5). */
public record EventGroup(
        String node,
        @JsonProperty("event_id") String eventId,
        String instance,
        Severity severity,
        @JsonProperty("native_severity") String nativeSeverity,
        long count,
        @JsonProperty("first_seen") String firstSeen,
        @JsonProperty("last_seen") String lastSeen,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> labels) {

    public EventGroup {
        node = node == null ? "" : node;
        eventId = eventId == null ? "" : eventId;
        instance = instance == null ? "" : instance;
        nativeSeverity = nativeSeverity == null ? "" : nativeSeverity;
        labels = labels == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(labels));
    }
}
