package it.infocube.ianus.plugin.core.buffer;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import it.infocube.ianus.plugin.core.aggregation.Aggregator;
import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.core.aggregation.Sample;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.window.TimeWindow;

/**
 * Buffer in memoria dei campioni per le sorgenti che non conservano lo storico
 * (es. Icinga 2): permette di rispondere su qualunque finestra entro la retention.
 */
public class SampleBuffer {

    public record SeriesKey(String node, String metric, String instance) {
    }

    private static final class Series {
        final String unit;
        final MetricKind kind;
        final Map<String, String> labels;
        final ArrayDeque<Sample> samples = new ArrayDeque<>();

        Series(String unit, MetricKind kind, Map<String, String> labels) {
            this.unit = unit;
            this.kind = kind;
            this.labels = labels;
        }
    }

    private final Map<SeriesKey, Series> series = new ConcurrentHashMap<>();

    public void add(SeriesKey key, String unit, MetricKind kind, Map<String, String> labels, Sample sample) {
        Series s = series.computeIfAbsent(key, k -> new Series(unit, kind, labels == null ? Map.of() : labels));
        synchronized (s) {
            s.samples.addLast(sample);
        }
    }

    /** Elimina i campioni piu' vecchi di before e le serie rimaste vuote. */
    public void prune(Instant before) {
        series.values().removeIf(s -> {
            synchronized (s) {
                while (!s.samples.isEmpty() && s.samples.peekFirst().ts().isBefore(before)) {
                    s.samples.pollFirst();
                }
                return s.samples.isEmpty();
            }
        });
    }

    public List<MetricSeries> aggregate(TimeWindow window, QueryFilter filter) {
        List<MetricSeries> out = new ArrayList<>();
        series.forEach((k, s) -> {
            if (!filter.nodeMatches(k.node()) || !filter.metricMatches(k.metric())) {
                return;
            }
            List<Sample> copy;
            synchronized (s) {
                copy = new ArrayList<>(s.samples);
            }
            out.add(MetricSeries.of(k.node(), k.metric(), k.instance(), s.unit,
                    Aggregator.of(s.kind, copy, window), s.labels));
        });
        return out;
    }
}
