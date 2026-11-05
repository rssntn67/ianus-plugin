package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.config.CollectorProperties;
import it.infocube.ianus.plugin.config.CollectorProperties.CollectSettings;
import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.collector.Collectors;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class CollectorsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:06:00Z"), ZoneOffset.UTC);

    private static class FakeAdapter implements SourceAdapter {

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
            return new SourceResult<>(List.of(), true);
        }

        @Override
        public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
            return new SourceResult<>(List.of(), true);
        }

        @Override
        public SourceHealth health() {
            return new SourceHealth("fake", "fake-01", true, "OK");
        }
    }

    private static Collectors collectors(Duration interval, Duration retention) {
        return collectors(new FakeAdapter(), new CollectSettings(interval, null), retention, CLOCK);
    }

    private static Collectors collectors(SourceAdapter adapter, CollectSettings collect, Duration retention,
                                         Clock clock) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("fake", adapter);
        ObjectProvider<SourceAdapter> adapters = factory.getBeanProvider(SourceAdapter.class);
        IanusProperties props = new IanusProperties("k", null, null, null, null, retention);
        CollectorProperties settings = new CollectorProperties(Map.of("fake", collect));
        return new Collectors(adapters, settings, props, new WindowResolver(props.windows(), clock), clock);
    }

    @Test
    void intervalMustDivideEveryWindow() {
        assertThatThrownBy(() -> collectors(Duration.ofMinutes(10), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fake");
    }

    @Test
    void retentionMustCoverLargestWindow() {
        assertThatThrownBy(() -> collectors(null, Duration.ofMinutes(20)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
    }

    @Test
    void retentionMustLeaveRoomForLargestWindowToBeCollected() {
        assertThatThrownBy(() -> collectors(null, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
        assertThat(collectors(null, Duration.ofMinutes(36)).store("fake")).isNotNull();
    }

    @Test
    void intervalMustBePositive() {
        for (Duration interval : List.of(Duration.ZERO, Duration.ofMinutes(-5))) {
            assertThatThrownBy(() -> collectors(interval, null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("collect-interval");
        }
    }

    @Test
    void delayMustNotBeNegative() {
        assertThatThrownBy(() -> collectors(new FakeAdapter(), new CollectSettings(null, Duration.ofMinutes(-1)),
                null, CLOCK)).isInstanceOf(IllegalStateException.class).hasMessageContaining("collect-delay");
    }

    @Test
    void errorInsideLoopDoesNotStopTheSource() {
        FakeAdapter failingOnce = new FakeAdapter() {
            private boolean failed;

            @Override
            public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
                if (!failed) {
                    failed = true;
                    throw new OutOfMemoryError("simulato");
                }
                return super.metrics(window, filter);
            }
        };
        Collectors collectors = collectors(failingOnce, new CollectSettings(Duration.ofSeconds(1), Duration.ZERO),
                null, Clock.systemUTC());

        collectors.start();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> collectors.store("fake").latest().isPresent());
        } finally {
            collectors.stop();
        }
    }

    @Test
    void validConfigurationBuildsOneStorePerSource() {
        assertThat(collectors(Duration.ofMinutes(1), null).store("fake").interval()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void settingsUseDefaults() {
        CollectSettings settings = new CollectorProperties(null).settings("sconosciuta");

        assertThat(settings.collectInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.collectDelay()).isEqualTo(Duration.ofMinutes(1));
    }
}
