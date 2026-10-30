package it.infocube.ianus.plugin.core.model;

import java.time.Instant;
import java.util.Map;

/** Singola occorrenza letta dall'NMS, prima del raggruppamento per finestra. */
public record RawEvent(
        String node,
        String eventId,
        String instance,
        Severity severity,
        String nativeSeverity,
        Instant time,
        String message,
        Map<String, String> labels) {
}
