package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.aggregation.Consolidator;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.Severity;
import org.junit.jupiter.api.Test;

class ConsolidatorTest {

    private static MetricSeries s(Double value, Double min, Double max, long samples) {
        return series("a", value, min, max, samples, Map.of());
    }

    private static MetricSeries series(String node, Double value, Double min, Double max, long samples,
                                       Map<String, String> labels) {
        return new MetricSeries(node, "m", "i", "u", value, min, max, samples, labels);
    }

    private static EventGroup event(long count, String first, String last, String message) {
        return new EventGroup("a", "e", "", Severity.ERROR, "Major", count, first, last, message, Map.of());
    }

    private static MetricSeries single(List<MetricSeries> series) {
        assertThat(series).hasSize(1);
        return series.get(0);
    }

    @Test
    void weightsValueBySamples() {
        MetricSeries r = single(Consolidator.metrics(List.of(s(10.0, 5.0, 20.0, 1), s(40.0, 30.0, 50.0, 3))));

        assertThat(r.value()).isEqualTo(32.5);
        assertThat(r.min()).isEqualTo(5.0);
        assertThat(r.max()).isEqualTo(50.0);
        assertThat(r.samples()).isEqualTo(4);
    }

    @Test
    void blockWithoutSamplesDoesNotWeigh() {
        MetricSeries r = single(Consolidator.metrics(List.of(s(10.0, 10.0, 10.0, 2), s(null, null, null, 0))));

        assertThat(r.value()).isEqualTo(10.0);
        assertThat(r.min()).isEqualTo(10.0);
        assertThat(r.max()).isEqualTo(10.0);
        assertThat(r.samples()).isEqualTo(2);
    }

    @Test
    void seriesWithoutAnySampleStaysNull() {
        MetricSeries r = single(Consolidator.metrics(List.of(s(null, null, null, 0), s(null, null, null, 0))));

        assertThat(r.value()).isNull();
        assertThat(r.min()).isNull();
        assertThat(r.max()).isNull();
        assertThat(r.samples()).isZero();
    }

    @Test
    void seriesWithDifferentLabelsStayDistinct() {
        List<MetricSeries> r = Consolidator.metrics(List.of(
                series("a", 1.0, 1.0, 1.0, 1, Map.of("vlan", "10")),
                series("a", 2.0, 2.0, 2.0, 1, Map.of("vlan", "20"))));

        assertThat(r).extracting(MetricSeries::value).containsExactlyInAnyOrder(1.0, 2.0);
    }

    @Test
    void seriesPresentOnlyInSomeBlocksKeepsItsData() {
        List<MetricSeries> r = Consolidator.metrics(List.of(
                series("a", 1.0, 1.0, 1.0, 1, Map.of()),
                series("a", 3.0, 3.0, 3.0, 1, Map.of()),
                series("b", 7.0, 6.0, 8.0, 2, Map.of())));

        assertThat(r).hasSize(2);
        assertThat(r).filteredOn(x -> x.node().equals("a")).singleElement()
                .satisfies(x -> assertThat(x.value()).isEqualTo(2.0));
        assertThat(r).filteredOn(x -> x.node().equals("b")).singleElement().satisfies(x -> {
            assertThat(x.value()).isEqualTo(7.0);
            assertThat(x.samples()).isEqualTo(2);
        });
    }

    @Test
    void eventsAreMergedAcrossBlocks() {
        List<EventGroup> r = Consolidator.events(List.of(
                event(2, "2026-10-14T10:01:00Z", "2026-10-14T10:04:00Z", "prima"),
                event(3, "2026-10-14T10:06:00Z", "2026-10-14T10:09:00Z", "dopo")));

        assertThat(r).singleElement().satisfies(e -> {
            assertThat(e.count()).isEqualTo(5);
            assertThat(e.firstSeen()).isEqualTo("2026-10-14T10:01:00Z");
            assertThat(e.lastSeen()).isEqualTo("2026-10-14T10:09:00Z");
            assertThat(e.message()).isEqualTo("dopo");
        });
    }
}
