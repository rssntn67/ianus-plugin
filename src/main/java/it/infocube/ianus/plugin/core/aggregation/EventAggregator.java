package it.infocube.ianus.plugin.core.aggregation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.util.Times;
import it.infocube.ianus.plugin.core.window.TimeWindow;

/** Raggruppamento degli eventi per (node, event_id, instance, severity), sezione 5. */
public final class EventAggregator {

    public static final Comparator<EventGroup> ORDER = Comparator.comparing(EventGroup::node)
            .thenComparing(EventGroup::eventId)
            .thenComparing(EventGroup::instance)
            .thenComparing(EventGroup::severity);

    private record Key(String node, String eventId, String instance, Severity severity) {
    }

    private static final class Acc {
        String nativeSeverity;
        long count;
        Instant first;
        Instant last;
        String message;
        Map<String, String> labels;
    }

    private EventAggregator() {
    }

    public static List<EventGroup> group(Collection<RawEvent> events, TimeWindow window) {
        Map<Key, Acc> groups = new LinkedHashMap<>();
        for (RawEvent e : events) {
            if (!window.contains(e.time())) {
                continue;
            }
            Key key = new Key(nz(e.node()), nz(e.eventId()), nz(e.instance()), e.severity());
            Acc acc = groups.computeIfAbsent(key, k -> new Acc());
            acc.count++;
            if (acc.first == null || e.time().isBefore(acc.first)) {
                acc.first = e.time();
            }
            if (acc.last == null || !e.time().isBefore(acc.last)) {
                acc.last = e.time();
                acc.message = e.message();
                acc.labels = e.labels();
                acc.nativeSeverity = e.nativeSeverity();
            }
        }
        List<EventGroup> out = new ArrayList<>();
        groups.forEach((k, a) -> out.add(new EventGroup(k.node(), k.eventId(), k.instance(), k.severity(),
                a.nativeSeverity, a.count, Times.format(a.first), Times.format(a.last), a.message, a.labels)));
        out.sort(ORDER);
        return out;
    }

    /** Fonde gruppi gia' calcolati che condividono la stessa chiave (usato da Prometheus). */
    public static List<EventGroup> merge(Collection<EventGroup> groups) {
        Map<Key, EventGroup> merged = new LinkedHashMap<>();
        for (EventGroup g : groups) {
            Key key = new Key(g.node(), g.eventId(), g.instance(), g.severity());
            merged.merge(key, g, (a, b) -> {
                boolean bIsLater = b.lastSeen().compareTo(a.lastSeen()) >= 0;
                EventGroup later = bIsLater ? b : a;
                String first = a.firstSeen().compareTo(b.firstSeen()) <= 0 ? a.firstSeen() : b.firstSeen();
                return new EventGroup(a.node(), a.eventId(), a.instance(), a.severity(), later.nativeSeverity(),
                        a.count() + b.count(), first, later.lastSeen(), later.message(), later.labels());
            });
        }
        List<EventGroup> out = new ArrayList<>(merged.values());
        out.sort(ORDER);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
