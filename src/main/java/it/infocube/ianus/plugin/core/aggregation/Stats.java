package it.infocube.ianus.plugin.core.aggregation;

import java.util.Collection;
import java.util.DoubleSummaryStatistics;

/** Media, minimo, massimo e numero di campioni; tutti null con samples = 0 se vuoto. */
public record Stats(Double avg, Double min, Double max, long samples) {

    public static final Stats EMPTY = new Stats(null, null, null, 0);

    public static Stats of(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        DoubleSummaryStatistics s = values.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        return new Stats(s.getAverage(), s.getMin(), s.getMax(), s.getCount());
    }
}
