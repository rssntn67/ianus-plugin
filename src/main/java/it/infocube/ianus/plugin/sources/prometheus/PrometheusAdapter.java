package it.infocube.ianus.plugin.sources.prometheus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import it.infocube.ianus.plugin.core.aggregation.EventAggregator;
import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.severity.SeverityMapper;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.util.Json;
import it.infocube.ianus.plugin.core.util.Times;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.sources.prometheus.PrometheusProperties.PromMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Plugin Prometheus. L'aggregazione e' delegata a PromQL:
 * gauge  -> avg/min/max/count_over_time(sel[W])
 * counter-> avg/min/max/count_over_time(rate(sel[R])[W:S])
 * valutate a end - 1 ms, cosi' l'intervallo coincide con [start, end).
 * Le serie note sono quelle con campioni nell'ultimo series-lookback prima della fine della finestra.
 * Eventi: serie ALERTS{alertstate="firing"} campionata nella finestra.
 */
@Component
@ConditionalOnProperty(prefix = "ianus.sources.prometheus", name = "enabled", havingValue = "true")
public class PrometheusAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusAdapter.class);
    private static final Pattern SIMPLE_SELECTOR = Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{.*})?$");

    private final PrometheusProperties props;
    private final PrometheusClient client;

    @Autowired
    public PrometheusAdapter(PrometheusProperties props) {
        this(props, new PrometheusClient(props));
    }

    PrometheusAdapter(PrometheusProperties props, PrometheusClient client) {
        this.props = props;
        this.client = client;
    }

    @Override
    public String system() {
        return "prometheus";
    }

    @Override
    public String instanceId() {
        return props.id();
    }

    @Override
    public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
        Instant at = window.end().minusMillis(1);
        List<MetricSeries> out = new ArrayList<>();
        boolean complete = true;
        for (PromMetric m : props.metrics()) {
            if (!filter.metricMatches(m.name())) {
                continue;
            }
            String range = rangeExpression(m, window.duration());
            Map<String, Map<String, String>> labelsByKey = new LinkedHashMap<>();
            try {
                Map<String, Double> avg = run("avg_over_time(" + range + ")", at, labelsByKey);
                Map<String, Double> min = run("min_over_time(" + range + ")", at, labelsByKey);
                Map<String, Double> max = run("max_over_time(" + range + ")", at, labelsByKey);
                Map<String, Double> count = run("count_over_time(" + range + ")", at, labelsByKey);
                // serie note ma senza campioni nella finestra: restano con valori null (sezione 3.3)
                run("count_over_time(" + rangeExpression(m, props.seriesLookback()) + ")", at, labelsByKey);
                labelsByKey.forEach((key, labels) -> {
                    Double n = count.get(key);
                    long samples = n == null ? 0 : Math.round(n);
                    out.add(toSeries(m, labels, samples == 0 ? null : avg.get(key),
                            samples == 0 ? null : min.get(key), samples == 0 ? null : max.get(key), samples));
                });
            } catch (ResourceAccessException e) {
                throw new SourceUnavailableException(system(), e.getMessage());
            } catch (RestClientException e) {
                complete = false;
                LOG.warn("Prometheus: metrica {} non letta: {}", m.name(), e.getMessage());
            }
        }
        return new SourceResult<>(out, complete);
    }

    /** Espressione di intervallo a cui applicare le funzioni *_over_time. */
    String rangeExpression(PromMetric m, Duration window) {
        if (m.kind() == MetricKind.COUNTER) {
            return "rate(" + m.selector() + "[" + promDuration(props.rateWindow()) + "])["
                    + promDuration(window) + ":" + promDuration(props.subqueryStep()) + "]";
        }
        if (SIMPLE_SELECTOR.matcher(m.selector().trim()).matches()) {
            return m.selector().trim() + "[" + promDuration(window) + "]";
        }
        // espressione composta: subquery
        return "(" + m.selector() + ")[" + promDuration(window) + ":" + promDuration(props.subqueryStep()) + "]";
    }

    private Map<String, Double> run(String expr, Instant at, Map<String, Map<String, String>> labelsByKey) {
        Map<String, Double> values = new HashMap<>();
        for (Map<String, Object> r : client.query(expr, at)) {
            Map<String, String> labels = new TreeMap<>();
            Json.map(r.get("metric")).forEach((k, v) -> {
                if (!"__name__".equals(k)) {
                    labels.put(k, Json.str(v));
                }
            });
            String key = labels.toString();
            labelsByKey.putIfAbsent(key, labels);
            List<Object> value = Json.list(r.get("value"));
            values.put(key, value.size() > 1 ? Json.finite(value.get(1)) : null);
        }
        return values;
    }

    private MetricSeries toSeries(PromMetric m, Map<String, String> labels, Double avg, Double min, Double max,
                                  long samples) {
        String node = labels.getOrDefault(props.nodeLabel(), "");
        String instance = m.instanceLabels().stream().map(l -> labels.getOrDefault(l, ""))
                .collect(Collectors.joining("/"));
        Map<String, String> rest = new TreeMap<>(labels);
        rest.remove(props.nodeLabel());
        m.instanceLabels().forEach(rest::remove);
        return new MetricSeries(node, m.name(), instance, m.unit(), avg, min, max, samples, rest);
    }

    @Override
    public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
        List<Map<String, Object>> series;
        try {
            // un passo prima della finestra, per sapere se l'alert era gia' attivo
            series = client.queryRange("ALERTS{alertstate=\"firing\"}", window.start().minus(props.alertStep()),
                    window.end().minusMillis(1), props.alertStep());
        } catch (RestClientException e) {
            throw new SourceUnavailableException(system(), e.getMessage());
        }
        double gap = props.alertStep().toMillis() / 1000.0 * 1.5;
        double windowStart = window.start().toEpochMilli() / 1000.0;
        List<EventGroup> groups = new ArrayList<>();
        for (Map<String, Object> s : series) {
            Map<String, String> labels = new TreeMap<>();
            Json.map(s.get("metric")).forEach((k, v) -> labels.put(k, Json.str(v)));
            long runs = 0;
            int continued = 0;
            Double before = null;
            Double prev = null;
            Double first = null;
            Double last = null;
            for (Object point : Json.list(s.get("values"))) {
                List<Object> pair = Json.list(point);
                Double t = pair.isEmpty() ? null : Json.num(pair.get(0));
                if (t == null) {
                    continue;
                }
                if (t < windowStart) {
                    before = t;
                    continue;
                }
                if (first == null && before != null && t - before <= gap) {
                    continued = 1;
                }
                if (prev == null || t - prev > gap) {
                    runs++;
                }
                if (first == null) {
                    first = t;
                }
                last = t;
                prev = t;
            }
            if (runs == 0) {
                continue;
            }
            String alertName = labels.getOrDefault("alertname", "");
            String nativeSeverity = labels.getOrDefault("severity", "");
            String node = labels.getOrDefault(props.nodeLabel(), "");
            Map<String, String> rest = new TreeMap<>(labels);
            List.of("__name__", "alertname", "alertstate", "severity", props.nodeLabel()).forEach(rest::remove);
            groups.add(new EventGroup(node, alertName, "", SeverityMapper.map("prometheus", nativeSeverity),
                    nativeSeverity, runs, Times.format(Times.fromEpochSeconds(first)),
                    Times.format(Times.fromEpochSeconds(last)), alertName, rest, continued));
        }
        return new SourceResult<>(EventAggregator.merge(groups), true);
    }

    @Override
    public SourceHealth health() {
        try {
            client.healthy();
            return new SourceHealth(system(), instanceId(), true, "OK");
        } catch (RestClientException e) {
            return new SourceHealth(system(), instanceId(), false, e.getMessage());
        }
    }

    static String promDuration(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + "s" : ms + "ms";
    }
}
