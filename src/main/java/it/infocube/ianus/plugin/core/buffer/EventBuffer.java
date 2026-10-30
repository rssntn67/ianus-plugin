package it.infocube.ianus.plugin.core.buffer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.window.TimeWindow;

/** Buffer in memoria degli eventi ricevuti, con retention. */
public class EventBuffer {

    private final List<RawEvent> events = new ArrayList<>();

    public synchronized void add(RawEvent event) {
        events.add(event);
    }

    public synchronized void prune(Instant before) {
        events.removeIf(e -> e.time().isBefore(before));
    }

    public synchronized List<RawEvent> in(TimeWindow window) {
        return events.stream().filter(e -> window.contains(e.time())).toList();
    }
}
