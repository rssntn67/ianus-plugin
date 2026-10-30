package it.infocube.ianus.plugin.core.source;

import java.util.List;
import java.util.Set;

/** Filtri opzionali node e metric (ripetibili). Insieme vuoto = nessun filtro. */
public record QueryFilter(Set<String> nodes, Set<String> metrics) {

    public static final QueryFilter NONE = new QueryFilter(Set.of(), Set.of());

    public QueryFilter {
        nodes = nodes == null ? Set.of() : Set.copyOf(nodes);
        metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
    }

    public static QueryFilter of(List<String> nodes, List<String> metrics) {
        return new QueryFilter(clean(nodes), clean(metrics));
    }

    public boolean nodeMatches(String node) {
        return nodes.isEmpty() || nodes.contains(node);
    }

    public boolean metricMatches(String metric) {
        return metrics.isEmpty() || metrics.contains(metric);
    }

    private static Set<String> clean(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return Set.copyOf(values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList());
    }
}
