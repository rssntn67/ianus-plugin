package it.infocube.ianus.plugin.sources.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.sources.prometheus.PrometheusProperties.PromMetric;
import org.junit.jupiter.api.Test;

class PrometheusAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));

    private final PromMetric traffic = new PromMetric("if_in_traffic", "node_network_receive_bytes_total",
            MetricKind.COUNTER, "bytes_per_second", List.of("device"));
    private final PromMetric cpu = new PromMetric("cpu_usage",
            "100 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[1m])) * 100",
            MetricKind.GAUGE, "percent", null);
    private final PromMetric mem = new PromMetric("mem", "node_memory_MemAvailable_bytes{job=\"node\"}",
            null, null, null);
    private final PrometheusProperties props = new PrometheusProperties(true, null, "http://localhost:9",
            null, null, null, null, null, false, null, null, null, null, null, List.of(traffic, cpu, mem));

    @Test
    void rangeExpressions() {
        PrometheusAdapter adapter = new PrometheusAdapter(props, new StubClient(props));
        assertThat(adapter.rangeExpression(traffic, Duration.ofMinutes(5)))
                .isEqualTo("rate(node_network_receive_bytes_total[60s])[300s:30s]");
        assertThat(adapter.rangeExpression(mem, Duration.ofMinutes(30)))
                .isEqualTo("node_memory_MemAvailable_bytes{job=\"node\"}[1800s]");
        assertThat(adapter.rangeExpression(cpu, Duration.ofMinutes(5)))
                .startsWith("(100 - avg").endsWith(")[300s:30s]");
    }

    @Test
    void buildsSeriesFromFourQueries() {
        PrometheusAdapter adapter = new PrometheusAdapter(props, new StubClient(props));
        List<MetricSeries> series = adapter.metrics(WINDOW, new QueryFilter(null, java.util.Set.of("if_in_traffic")))
                .items();
        assertThat(series).singleElement().satisfies(s -> {
            assertThat(s.node()).isEqualTo("srv-1:9100");
            assertThat(s.instance()).isEqualTo("eth0");
            assertThat(s.value()).isEqualTo(150.0);
            assertThat(s.min()).isEqualTo(100.0);
            assertThat(s.max()).isEqualTo(200.0);
            assertThat(s.samples()).isEqualTo(10);
            assertThat(s.labels()).containsEntry("job", "node").doesNotContainKey("device");
        });
    }

    @Test
    void alertsBecomeEventGroups() {
        PrometheusAdapter adapter = new PrometheusAdapter(props, new StubClient(props));
        List<EventGroup> events = adapter.events(WINDOW, QueryFilter.NONE).items();
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.eventId()).isEqualTo("HighLoad");
            assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(e.count()).isEqualTo(2);
            assertThat(e.firstSeen()).isEqualTo("2026-10-14T10:00:00Z");
            assertThat(e.lastSeen()).isEqualTo("2026-10-14T10:03:00Z");
        });
    }

    static class StubClient extends PrometheusClient {
        StubClient(PrometheusProperties p) {
            super(p);
        }

        @Override
        public List<Map<String, Object>> query(String expr, Instant at) {
            String v = expr.startsWith("avg") ? "150" : expr.startsWith("min") ? "100"
                    : expr.startsWith("max") ? "200" : "10";
            return List.of(Map.<String, Object>of(
                    "metric", Map.of("instance", "srv-1:9100", "device", "eth0", "job", "node"),
                    "value", List.<Object>of(at.getEpochSecond(), v)));
        }

        @Override
        public List<Map<String, Object>> queryRange(String expr, Instant start, Instant end, Duration step) {
            double t0 = start.getEpochSecond();
            return List.of(Map.<String, Object>of(
                    "metric", Map.of("__name__", "ALERTS", "alertname", "HighLoad", "alertstate", "firing",
                            "severity", "critical", "instance", "srv-1:9100"),
                    "values", List.<Object>of(List.<Object>of(t0, "1"), List.<Object>of(t0 + 15, "1"),
                            List.<Object>of(t0 + 180, "1"))));
        }
    }
}
