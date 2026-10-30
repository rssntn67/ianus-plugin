package it.infocube.ianus.plugin.core.window;

import java.time.Duration;
import java.time.Instant;

import it.infocube.ianus.plugin.core.model.WindowInfo;
import it.infocube.ianus.plugin.core.util.Times;

/** Finestra [start, end): comprende l'inizio ed esclude la fine (sezione 3.2). */
public record TimeWindow(Instant start, Instant end, Duration duration) {

    public boolean contains(Instant t) {
        return t != null && !t.isBefore(start) && t.isBefore(end);
    }

    public WindowInfo toInfo() {
        return new WindowInfo(Times.format(start), Times.format(end), duration.toString());
    }
}
