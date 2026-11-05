package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.collector.BlockStore;
import it.infocube.ianus.plugin.core.collector.SourceCollector;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceCapabilities;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SourceCollectorTest {

    private static final Duration FIVE = Duration.ofMinutes(5);

    private static final class MutableClock extends Clock {
        Instant now = at("10:06:00");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeAdapter implements SourceAdapter {
        final List<Instant> reads = new ArrayList<>();
        RuntimeException failure;
        boolean metricsComplete = true;
        boolean eventsComplete = true;

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
            reads.add(window.start());
            if (failure != null) {
                throw failure;
            }
            assertThat(filter).isEqualTo(QueryFilter.NONE);
            assertThat(window.duration()).isEqualTo(FIVE);
            return new SourceResult<>(List.of(new MetricSeries("a", "m", "", "u", 1.0, 1.0, 1.0, 1, Map.of())),
                    metricsComplete);
        }

        @Override
        public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
            return new SourceResult<>(List.of(), eventsComplete);
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

    private final MutableClock clock = new MutableClock();
    private final FakeAdapter adapter = new FakeAdapter();
    private final BlockStore store = new BlockStore(FIVE, Duration.ofHours(3), clock);
    private final SourceCollector collector = new SourceCollector(adapter, store, Duration.ofMinutes(1),
            Duration.ofHours(3), clock);

    private static Instant at(String time) {
        return Instant.parse("2026-10-14T" + time + "Z");
    }

    @Test
    void collectsLastBlockAfterDelay(CapturedOutput output) {
        collector.collectDue();

        assertThat(adapter.reads).containsExactly(at("10:00:00"));
        assertThat(store.get(at("10:00:00"))).hasValueSatisfying(b -> {
            assertThat(b.end()).isEqualTo(at("10:05:00"));
            assertThat(b.complete()).isTrue();
            assertThat(b.metrics()).hasSize(1);
        });
        assertThat(output.getOut()).contains("fake: metrics letti in ");
    }

    @Test
    void waitsForDelayBeforeCollecting() {
        clock.now = at("10:05:30");

        collector.collectDue();

        assertThat(adapter.reads).containsExactly(at("09:55:00"));
    }

    @Test
    void delayLongerThanIntervalPicksRightBlock() {
        clock.now = at("10:12:00");
        SourceCollector late = new SourceCollector(adapter, store, Duration.ofMinutes(7), Duration.ofHours(3), clock);

        late.collectDue();

        assertThat(adapter.reads).containsExactly(at("10:00:00"));
    }

    @Test
    void doesNotReadSameBlockTwice() {
        collector.collectDue();
        collector.collectDue();

        assertThat(adapter.reads).hasSize(1);
    }

    @Test
    void blockIsIncompleteIfMetricsOrEventsAre() {
        adapter.eventsComplete = false;
        collector.collectDue();
        assertThat(store.get(at("10:00:00"))).hasValueSatisfying(b -> assertThat(b.complete()).isFalse());

        adapter.eventsComplete = true;
        adapter.metricsComplete = false;
        clock.now = at("10:11:00");
        collector.collectDue();
        assertThat(store.get(at("10:05:00"))).hasValueSatisfying(b -> assertThat(b.complete()).isFalse());
    }

    @Test
    void unreachableNmsSavesNothing() {
        adapter.failure = new SourceUnavailableException("fake", "giu'");

        collector.collectDue();

        assertThat(store.latest()).isEmpty();
    }

    @Test
    void unexpectedExceptionDoesNotStopCollector() {
        adapter.failure = new IllegalStateException("imprevisto");
        collector.collectDue();
        assertThat(store.latest()).isEmpty();

        adapter.failure = null;
        clock.now = at("10:11:00");
        collector.collectDue();

        assertThat(store.get(at("10:05:00"))).isPresent();
    }

    @Test
    void unexpectedExceptionDuringRecoveryStopsIt() {
        collector.collectDue();
        adapter.failure = new IllegalStateException("imprevisto");

        assertThat(collector.recoverOne()).isFalse();
    }

    @Test
    void recoveryReadsMissingBlocksFromNewest() {
        collector.collectDue();

        assertThat(collector.recoverOne()).isTrue();
        assertThat(collector.recoverOne()).isTrue();

        assertThat(adapter.reads).containsExactly(at("10:00:00"), at("09:55:00"), at("09:50:00"));
    }

    @Test
    void skippedBlockIsRecovered() {
        collector.collectDue();
        clock.now = at("10:16:00");
        collector.collectDue();

        assertThat(collector.recoverOne()).isTrue();

        assertThat(adapter.reads).containsExactly(at("10:00:00"), at("10:10:00"), at("10:05:00"));
    }

    @Test
    void failedDueBlockIsRecovered() {
        adapter.failure = new SourceUnavailableException("fake", "giu'");
        collector.collectDue();
        adapter.failure = null;

        assertThat(collector.recoverOne()).isTrue();

        assertThat(store.get(at("10:00:00"))).isPresent();
    }

    @Test
    void recoveryStopsWhenNmsIsUnreachable() {
        adapter.failure = new SourceUnavailableException("fake", "giu'");

        assertThat(collector.recoverOne()).isFalse();
        assertThat(store.latest()).isEmpty();
    }

    @Test
    void recoveryStaysInsideRetention() {
        SourceCollector shortOne = new SourceCollector(adapter, store, Duration.ofMinutes(1),
                Duration.ofMinutes(15), clock);
        shortOne.collectDue();

        assertThat(shortOne.recoverOne()).isTrue();
        assertThat(shortOne.recoverOne()).isFalse();

        assertThat(adapter.reads).containsExactly(at("10:00:00"), at("09:55:00"));
    }

    @Test
    void incompleteBlockIsReadAtMostThreeMoreTimes() {
        adapter.metricsComplete = false;
        SourceCollector shortOne = new SourceCollector(adapter, store, Duration.ofMinutes(1),
                Duration.ofMinutes(10), clock);
        shortOne.collectDue();

        assertThat(shortOne.recoverOne()).isTrue();
        assertThat(shortOne.recoverOne()).isTrue();
        assertThat(shortOne.recoverOne()).isTrue();
        assertThat(shortOne.recoverOne()).isFalse();

        assertThat(adapter.reads).hasSize(4);
    }

    @Test
    void nextRunIsEndOfNextBlockPlusDelay() {
        assertThat(collector.nextRun()).isEqualTo(at("10:11:00"));
    }
}
