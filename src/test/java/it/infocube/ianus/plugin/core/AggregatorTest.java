package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import it.infocube.ianus.plugin.core.aggregation.Aggregator;
import it.infocube.ianus.plugin.core.aggregation.Sample;
import it.infocube.ianus.plugin.core.aggregation.Stats;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;

class AggregatorTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private final TimeWindow window = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));

    @Test
    void gaugeUsesOnlySamplesInsideWindow() {
        Stats s = Aggregator.gauge(List.of(
                new Sample(T0.minusSeconds(60), 1000),
                new Sample(T0, 10),
                new Sample(T0.plusSeconds(60), 30),
                new Sample(T0.plusSeconds(120), 20),
                new Sample(T0.plusSeconds(300), 1000)), window);
        assertThat(s.avg()).isEqualTo(20.0);
        assertThat(s.min()).isEqualTo(10.0);
        assertThat(s.max()).isEqualTo(30.0);
        assertThat(s.samples()).isEqualTo(3);
    }

    @Test
    void emptyWindowGivesNulls() {
        Stats s = Aggregator.gauge(List.of(), window);
        assertThat(s.avg()).isNull();
        assertThat(s.min()).isNull();
        assertThat(s.max()).isNull();
        assertThat(s.samples()).isZero();
    }

    @Test
    void counterUsesRatesAndDiscardsResets() {
        Stats s = Aggregator.counter(List.of(
                new Sample(T0.minusSeconds(60), 0),
                new Sample(T0.plusSeconds(0), 600),      // 10/s (usa il campione precedente)
                new Sample(T0.plusSeconds(60), 1800),    // 20/s
                new Sample(T0.plusSeconds(120), 100),    // azzeramento: scartato
                new Sample(T0.plusSeconds(180), 1900)),  // 30/s
                window);
        assertThat(s.samples()).isEqualTo(3);
        assertThat(s.avg()).isCloseTo(20.0, within(1e-9));
        assertThat(s.min()).isEqualTo(10.0);
        assertThat(s.max()).isEqualTo(30.0);
    }
}
