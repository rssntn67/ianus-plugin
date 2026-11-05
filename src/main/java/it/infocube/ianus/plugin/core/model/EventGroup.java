package it.infocube.ianus.plugin.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Gruppo di eventi della finestra (sezione 5). continued non fa parte del contratto: conta i periodi
 * di attivazione gia' in corso prima del blocco, che il consolidamento non deve contare due volte.
 */
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
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> labels,
        @JsonIgnore @Schema(hidden = true) int continued) {

    public EventGroup {
        node = node == null ? "" : node;
        eventId = eventId == null ? "" : eventId;
        instance = instance == null ? "" : instance;
        nativeSeverity = nativeSeverity == null ? "" : nativeSeverity;
        labels = labels == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(labels));
    }

    public EventGroup(String node, String eventId, String instance, Severity severity, String nativeSeverity,
                      long count, String firstSeen, String lastSeen, String message, Map<String, String> labels) {
        this(node, eventId, instance, severity, nativeSeverity, count, firstSeen, lastSeen, message, labels, 0);
    }

    /** Stesso gruppo senza le occorrenze gia' contate nel blocco precedente della finestra. */
    public EventGroup withoutContinued() {
        return new EventGroup(node, eventId, instance, severity, nativeSeverity, count - continued, firstSeen,
                lastSeen, message, labels, 0);
    }
}
