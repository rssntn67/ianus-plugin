package it.infocube.ianus.plugin.core.aggregation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import it.infocube.ianus.plugin.core.window.TimeWindow;

/** Regole di aggregazione della sezione 3.3. */
public final class Aggregator {

    private Aggregator() {
    }

    public static Stats of(MetricKind kind, Collection<Sample> samples, TimeWindow window) {
        return kind == MetricKind.COUNTER ? counter(samples, window) : gauge(samples, window);
    }

    /** Grandezze istantanee: media, minimo e massimo dei campioni nella finestra. */
    public static Stats gauge(Collection<Sample> samples, TimeWindow window) {
        List<Double> values = samples.stream()
                .filter(s -> window.contains(s.ts()))
                .map(Sample::value)
                .filter(Double::isFinite)
                .toList();
        return Stats.of(values);
    }

    /**
     * Contatori: tasso al secondo tra campioni consecutivi, poi media/min/max dei tassi.
     * Conta ogni tasso il cui campione finale cade nella finestra (puo' usare il campione
     * precedente all'inizio). Gli azzeramenti (delta negativo) vengono scartati.
     */
    public static Stats counter(Collection<Sample> samples, TimeWindow window) {
        List<Sample> sorted = samples.stream()
                .filter(s -> Double.isFinite(s.value()))
                .sorted(Comparator.comparing(Sample::ts))
                .toList();
        List<Double> rates = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Sample prev = sorted.get(i - 1);
            Sample cur = sorted.get(i);
            if (!window.contains(cur.ts())) {
                continue;
            }
            double seconds = (cur.ts().toEpochMilli() - prev.ts().toEpochMilli()) / 1000.0;
            double delta = cur.value() - prev.value();
            if (seconds <= 0 || delta < 0) {
                continue;
            }
            rates.add(delta / seconds);
        }
        return Stats.of(rates);
    }
}
