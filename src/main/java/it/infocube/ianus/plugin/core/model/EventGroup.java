package it.infocube.ianus.plugin.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Gruppo di eventi della finestra (sezione 5). */
public record EventGroup(
        @JsonProperty(required = true) String node,
        @JsonProperty(value = "event_id", required = true) String eventId,
        @JsonProperty(required = true) String instance,
        @JsonProperty(required = true) Severity severity,
        @JsonProperty(value = "native_severity", required = true) String nativeSeverity,
        @JsonProperty(required = true) long count,
        @JsonProperty(value = "first_seen", required = true) String firstSeen,
        @JsonProperty(value = "last_seen", required = true) String lastSeen,
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
