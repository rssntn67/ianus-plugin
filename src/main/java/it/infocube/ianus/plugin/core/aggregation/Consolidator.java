package it.infocube.ianus.plugin.core.aggregation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;

/**
 * Consolida i blocchi raccolti sulla finestra richiesta. L'input e' l'unione delle voci di tutti
 * i blocchi. Il risultato coincide con il calcolo sui campioni originali: la media e' pesata con
 * samples e i blocchi senza campioni non pesano.
 */
public final class Consolidator {

    private record Key(String node, String metric, String instance, Map<String, String> labels) {
    }

    private static final class Acc {
        final MetricSeries first;
        double weighted;
        Double min;
        Double max;
        long samples;

        Acc(MetricSeries first) {
            this.first = first;
        }

        void add(MetricSeries s) {
            if (s.samples() == 0 || s.value() == null) {
                return;
            }
            weighted += s.value() * s.samples();
            samples += s.samples();
            min = min == null || s.min() < min ? s.min() : min;
            max = max == null || s.max() > max ? s.max() : max;
        }

        MetricSeries toSeries() {
            return new MetricSeries(first.node(), first.metric(), first.instance(), first.unit(),
                    samples == 0 ? null : weighted / samples, min, max, samples, first.labels());
        }
    }

    private Consolidator() {
    }

    public static List<MetricSeries> metrics(Collection<MetricSeries> series) {
        Map<Key, Acc> groups = new LinkedHashMap<>();
        for (MetricSeries s : series) {
            groups.computeIfAbsent(new Key(s.node(), s.metric(), s.instance(), s.labels()), k -> new Acc(s)).add(s);
        }
        List<MetricSeries> out = new ArrayList<>(groups.size());
        groups.values().forEach(a -> out.add(a.toSeries()));
        return out;
    }

    public static List<EventGroup> events(Collection<EventGroup> groups) {
        return EventAggregator.merge(groups);
    }
}
