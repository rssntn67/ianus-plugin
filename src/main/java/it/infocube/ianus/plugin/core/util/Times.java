package it.infocube.ianus.plugin.core.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Conversioni di istanti: in uscita sempre RFC 3339 in UTC con suffisso Z. */
public final class Times {

    private static final DateTimeFormatter OPENNMS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    public static Instant fromEpochSeconds(double seconds) {
        return Instant.ofEpochMilli(Math.round(seconds * 1000.0));
    }

    /** Formato data usato nei filtri FIQL di OpenNMS (es. 2026-10-14T10:00:00.000+0000). */
    public static String opennms(Instant instant) {
        return OPENNMS.format(instant);
    }

    /** Epoch in millisecondi (numero o stringa) oppure data ISO 8601 / formato OpenNMS. */
    public static Instant parseFlexible(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(t));
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignored) {
            // prova i formati successivi
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // prova il formato OpenNMS
        }
        try {
            return OffsetDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
