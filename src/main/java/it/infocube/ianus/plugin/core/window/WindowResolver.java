package it.infocube.ianus.plugin.core.window;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

import it.infocube.ianus.plugin.core.error.BadRequestException;

/**
 * Interpreta i parametri window ed end (sezioni 3.1 e 3.2): finestre allineate a multipli
 * della loro ampiezza; senza end si usa l'ultima finestra conclusa.
 */
public class WindowResolver {

    private final List<Duration> supported;
    private final Clock clock;

    public WindowResolver(List<Duration> supported, Clock clock) {
        this.supported = List.copyOf(supported);
        this.clock = clock;
    }

    public List<Duration> supported() {
        return supported;
    }

    public TimeWindow resolve(String windowParam, String endParam) {
        if (windowParam == null || windowParam.isBlank()) {
            throw new BadRequestException("missing_window", "Il parametro window e' obbligatorio (es. PT5M)");
        }
        Duration duration;
        try {
            duration = Duration.parse(windowParam.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("invalid_window", "window non e' una durata ISO 8601: " + windowParam);
        }
        if (!supported.contains(duration)) {
            throw new BadRequestException("unsupported_window", "Finestra non supportata: " + windowParam
                    + ". Supportate: " + supported.stream().map(Duration::toString).collect(Collectors.joining(", ")));
        }
        long step = duration.toMillis();
        Instant now = clock.instant();
        Instant end;
        if (endParam == null || endParam.isBlank()) {
            end = Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), step) * step);
        } else {
            try {
                end = OffsetDateTime.parse(endParam.trim()).toInstant();
            } catch (DateTimeParseException e) {
                throw new BadRequestException("invalid_end", "end non e' un istante RFC 3339: " + endParam);
            }
            if (end.getNano() % 1_000_000 != 0 || Math.floorMod(end.toEpochMilli(), step) != 0) {
                throw new BadRequestException("unaligned_end",
                        "end non e' allineato a un multiplo di " + duration + ": " + endParam);
            }
            if (end.isAfter(now)) {
                throw new BadRequestException("future_end", "La finestra richiesta non e' ancora conclusa: " + endParam);
            }
        }
        return new TimeWindow(end.minus(duration), end, duration);
    }
}
