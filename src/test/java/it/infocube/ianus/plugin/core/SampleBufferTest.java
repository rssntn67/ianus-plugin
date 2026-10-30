package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.core.aggregation.Sample;
import it.infocube.ianus.plugin.core.buffer.SampleBuffer;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;

class SampleBufferTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void aggregatesAndPrunes() {
        SampleBuffer buffer = new SampleBuffer();
        SampleBuffer.SeriesKey key = new SampleBuffer.SeriesKey("srv-db-01", "rta", "ping4");
        buffer.add(key, "seconds", MetricKind.GAUGE, Map.of(), new Sample(T0.plusSeconds(30), 0.002));
        buffer.add(key, "seconds", MetricKind.GAUGE, Map.of(), new Sample(T0.plusSeconds(90), 0.004));
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));

        List<MetricSeries> out = buffer.aggregate(w, QueryFilter.NONE);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).value()).isCloseTo(0.003, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(out.get(0).samples()).isEqualTo(2);

        buffer.prune(T0.plusSeconds(3600));
        assertThat(buffer.aggregate(w, QueryFilter.NONE)).isEmpty();
    }
}
