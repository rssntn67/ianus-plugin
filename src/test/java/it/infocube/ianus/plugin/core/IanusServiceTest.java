package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.aggregation.Stats;
import it.infocube.ianus.plugin.core.error.BadRequestException;
import it.infocube.ianus.plugin.core.error.SourceNotFoundException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.MetricsResponse;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.service.IanusService;
import it.infocube.ianus.plugin.core.service.ResultCache;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceCapabilities;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class IanusServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:07:30Z"), ZoneOffset.UTC);
    private final IanusProperties props = new IanusProperties("k", null, null, 2, 10, Duration.ZERO, null, null);
    private final FakeAdapter adapter = new FakeAdapter();
    private final IanusService service = new IanusService(provider(adapter),
            new WindowResolver(props.windows(), clock), new ResultCache(Duration.ZERO, 10, clock), props);

    @Test
    void envelopeFilterAndPagination() {
        MetricsResponse r = service.metrics("fake", "PT5M", null, null, null, 0, 2);
        assertThat(r.source().system()).isEqualTo("fake");
        assertThat(r.source().pluginVersion()).isEqualTo("2.0.0");
        assertThat(r.window().start()).isEqualTo("2026-10-14T10:00:00Z");
        assertThat(r.page().totalElements()).isEqualTo(3);
        assertThat(r.metrics()).hasSize(2);
        assertThat(r.metrics().get(0).node()).isEqualTo("a");

        MetricsResponse second = service.metrics("fake", "PT5M", null, null, null, 1, 2);
        assertThat(second.metrics()).hasSize(1);

        MetricsResponse filtered = service.metrics("fake", "PT5M", null, List.of("b"), null, null, null);
        assertThat(filtered.metrics()).extracting(MetricSeries::node).containsOnly("b");
    }

    @Test
    void nmsCallIsLoggedWithDurationOnlyOnCacheMiss(CapturedOutput output) {
        IanusService cached = new IanusService(provider(adapter), new WindowResolver(props.windows(), clock),
                new ResultCache(Duration.ofMinutes(10), 10, clock), props);

        cached.metrics("fake", "PT5M", null, null, null, null, null);
        cached.metrics("fake", "PT5M", null, null, null, null, null);

        assertThat(output.getOut()).containsOnlyOnce("fake: metrics letti in ").containsPattern("in \\d+ ms");
    }

    @Test
    void unknownSourceAndBadPaging() {
        assertThatThrownBy(() -> service.metrics("nope", "PT5M", null, null, null, null, null))
                .isInstanceOf(SourceNotFoundException.class);
        assertThatThrownBy(() -> service.metrics("fake", "PT5M", null, null, null, 0, 11))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void healthIsDegradedWhenSourceUnreachable() {
        adapter.reachable = false;
        assertThat(service.health().status()).isEqualTo("DEGRADED");
    }

    private static ObjectProvider<SourceAdapter> provider(SourceAdapter a) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("fake", a);
        return factory.getBeanProvider(SourceAdapter.class);
    }

    static class FakeAdapter implements SourceAdapter {
        boolean reachable = true;

        @Override
        public String system() {
            return "fake";
        }

        @Override
        public String instanceId() {
            return "fake-01";
        }

        @Override
        public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
            List<MetricSeries> out = new ArrayList<>();
            Stats s = new Stats(1.0, 1.0, 1.0, 1);
            out.add(MetricSeries.of("b", "cpu_usage", "1", "percent", s, Map.of()));
            out.add(MetricSeries.of("a", "cpu_usage", "2", "percent", s, Map.of()));
            out.add(MetricSeries.of("a", "cpu_usage", "1", "percent", s, Map.of()));
            return new SourceResult<>(out, true);
        }

        @Override
        public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
            return new SourceResult<>(List.of(), true);
        }

        @Override
        public SourceCapabilities capabilities() {
            return new SourceCapabilities(List.of("cpu_usage"), List.of("a", "b"), Duration.ofDays(1));
        }

        @Override
        public SourceHealth health() {
            return new SourceHealth("fake", "fake-01", reachable, reachable ? "OK" : "down");
        }
    }
}
