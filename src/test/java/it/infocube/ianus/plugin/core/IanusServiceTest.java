package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.config.CollectorProperties;
import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.collector.Block;
import it.infocube.ianus.plugin.core.collector.Collectors;
import it.infocube.ianus.plugin.core.error.BadRequestException;
import it.infocube.ianus.plugin.core.error.SourceNotFoundException;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.CapabilitiesResponse;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.EventsResponse;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.MetricsResponse;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.service.IanusService;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class IanusServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:07:30Z"), ZoneOffset.UTC);
    private final IanusProperties props = new IanusProperties("k", null, null, 2, 10, null);
    private final FakeAdapter adapter = new FakeAdapter();
    private final Collectors collectors = collectors(clock);
    private final IanusService service = service(collectors, clock);

    private Collectors collectors(Clock c) {
        return new Collectors(provider(adapter), new CollectorProperties(null), props,
                new WindowResolver(props.windows(), c), c);
    }

    private IanusService service(Collectors c, Clock time) {
        return new IanusService(provider(adapter), new WindowResolver(props.windows(), time), c, props, time);
    }

    private static Instant at(int minute) {
        return T0.plus(Duration.ofMinutes(minute));
    }

    private static MetricSeries series(String node, String instance, double value) {
        return new MetricSeries(node, "cpu_usage", instance, "percent", value, value, value, 1, Map.of());
    }

    private static Block block(int minute, boolean complete, MetricSeries... series) {
        return new Block(at(minute), at(minute + 5), List.of(series), List.of(), complete);
    }

    private void fillHalfHour(int skipped) {
        for (int minute = -30; minute < 0; minute += 5) {
            if (minute != skipped) {
                collectors.store("fake").put(block(minute, true, series("a", "1", 10.0)));
            }
        }
    }

    @Test
    void envelopeFilterAndPagination() {
        collectors.store("fake").put(block(0, true, series("b", "1", 1.0), series("a", "2", 1.0),
                series("a", "1", 1.0)));

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
    void unknownSourceAndBadPaging() {
        assertThatThrownBy(() -> service.metrics("nope", "PT5M", null, null, null, null, null))
                .isInstanceOf(SourceNotFoundException.class);
        assertThatThrownBy(() -> service.metrics("fake", "PT5M", null, null, null, 0, 11))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void allBlocksPresentGiveCompleteAnswer() {
        fillHalfHour(1);

        MetricsResponse r = service.metrics("fake", "PT30M", null, null, null, null, null);

        assertThat(r.complete()).isTrue();
        assertThat(r.metrics()).singleElement().satisfies(s -> {
            assertThat(s.value()).isEqualTo(10.0);
            assertThat(s.samples()).isEqualTo(6);
        });
    }

    @Test
    void missingBlockGivesIncompleteAnswerWithData() {
        fillHalfHour(-10);

        MetricsResponse r = service.metrics("fake", "PT30M", null, null, null, null, null);

        assertThat(r.complete()).isFalse();
        assertThat(r.metrics()).singleElement().satisfies(s -> assertThat(s.samples()).isEqualTo(5));
    }

    @Test
    void incompleteBlockGivesIncompleteAnswer() {
        fillHalfHour(-10);
        collectors.store("fake").put(block(-10, false));

        assertThat(service.metrics("fake", "PT30M", null, null, null, null, null).complete()).isFalse();
    }

    @Test
    void eventsAreConsolidatedOverTheWindow() {
        for (int minute : new int[] {-30, -25, -20, -15, -10, -5}) {
            collectors.store("fake").put(new Block(at(minute), at(minute + 5), List.of(),
                    List.of(new EventGroup("a", "e", "", Severity.ERROR, "Major", 2,
                            at(minute).toString(), at(minute).toString(), null, Map.of())), true));
        }

        EventsResponse r = service.events("fake", "PT30M", null, null, null, null);

        assertThat(r.complete()).isTrue();
        assertThat(r.events()).singleElement().satisfies(e -> assertThat(e.count()).isEqualTo(12));
    }

    @Test
    void noBlockInWindowIs503() {
        assertThatThrownBy(() -> service.metrics("fake", "PT5M", null, null, null, null, null))
                .isInstanceOf(SourceUnavailableException.class);
        assertThatThrownBy(() -> service.events("fake", "PT5M", null, null, null, null))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void windowOlderThanRetentionIs400() {
        assertThatThrownBy(() -> service.metrics("fake", "PT5M", "2026-10-14T06:00:00Z", null, null, null, null))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.code()).isEqualTo("window_too_old"));
    }

    @Test
    void windowStartingAtRetentionLimitIsServed() {
        Clock later = Clock.fixed(at(185), ZoneOffset.UTC);
        Collectors c = collectors(later);
        c.store("fake").put(block(5, true, series("a", "1", 1.0)));

        MetricsResponse r = service(c, later).metrics("fake", "PT5M", "2026-10-14T10:10:00Z", null, null, null, null);

        assertThat(r.complete()).isTrue();
        assertThat(r.metrics()).hasSize(1);
    }

    @Test
    void requestsNeverCallTheAdapter() {
        collectors.store("fake").put(block(0, true, series("a", "1", 1.0)));

        service.metrics("fake", "PT5M", null, null, null, null, null);
        service.events("fake", "PT5M", null, null, null, null);
        service.capabilities("fake");

        assertThat(adapter.reads).isZero();
    }

    @Test
    void capabilitiesComeFromLatestBlock() {
        collectors.store("fake").put(block(-5, true, series("vecchio", "1", 1.0)));
        collectors.store("fake").put(new Block(at(0), at(5), List.of(series("b", "1", 1.0), series("a", "1", 1.0)),
                List.of(new EventGroup("c", "e", "", Severity.ERROR, "Major", 1, "t", "t", null, Map.of())), true));

        CapabilitiesResponse r = service.capabilities("fake");

        assertThat(r.metrics()).containsExactly("cpu_usage");
        assertThat(r.nodes()).containsExactly("a", "b", "c");
        assertThat(r.historyDepth()).isEqualTo("PT3H");
        assertThat(r.windows()).containsExactly("PT5M", "PT30M");
    }

    @Test
    void capabilitiesWithoutBlocksAreEmpty() {
        CapabilitiesResponse r = service.capabilities("fake");

        assertThat(r.metrics()).isEmpty();
        assertThat(r.nodes()).isEmpty();
    }

    @Test
    void healthIsDegradedWhenSourceUnreachable() {
        adapter.reachable = false;
        assertThat(service.health().status()).isEqualTo("DEGRADED");
    }

    @Test
    void healthReportsLastCollected() {
        assertThat(service.health().sources()).singleElement()
                .satisfies(s -> assertThat(s.lastCollected()).isNull());

        collectors.store("fake").put(block(0, true));

        assertThat(service.health().sources()).singleElement()
                .satisfies(s -> assertThat(s.lastCollected()).isEqualTo("2026-10-14T10:05:00Z"));
    }

    private static ObjectProvider<SourceAdapter> provider(SourceAdapter a) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("fake", a);
        return factory.getBeanProvider(SourceAdapter.class);
    }

    static class FakeAdapter implements SourceAdapter {
        boolean reachable = true;
        int reads;

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
            reads++;
            return new SourceResult<>(List.of(), true);
        }

        @Override
        public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
            reads++;
            return new SourceResult<>(List.of(), true);
        }

        @Override
        public SourceHealth health() {
            return new SourceHealth("fake", "fake-01", reachable, reachable ? "OK" : "down");
        }
    }
}
