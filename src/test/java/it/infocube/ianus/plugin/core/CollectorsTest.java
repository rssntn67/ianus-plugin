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
import it.infocube.ianus.plugin.config.CollectorProperties.CollectSettings;
import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.collector.Collectors;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceCapabilities;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class CollectorsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:06:00Z"), ZoneOffset.UTC);

    private static final class FakeAdapter implements SourceAdapter {

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
        public SourceCapabilities capabilities() {
            return new SourceCapabilities(List.of(), List.of(), Duration.ZERO);
        }

        @Override
        public SourceHealth health() {
            return new SourceHealth("fake", "fake-01", true, "OK");
        }
    }

    private static Collectors collectors(Duration interval, Duration retention) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("fake", new FakeAdapter());
        ObjectProvider<SourceAdapter> adapters = factory.getBeanProvider(SourceAdapter.class);
        IanusProperties props = new IanusProperties("k", null, null, null, null, null, null, retention);
        CollectorProperties settings = new CollectorProperties(Map.of("fake", new CollectSettings(interval, null)));
        return new Collectors(adapters, settings, props, new WindowResolver(props.windows(), CLOCK), CLOCK);
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
