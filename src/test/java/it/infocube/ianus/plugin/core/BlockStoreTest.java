package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import it.infocube.ianus.plugin.core.collector.Block;
import it.infocube.ianus.plugin.core.collector.BlockStore;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.Severity;
import org.junit.jupiter.api.Test;

class BlockStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Duration FIVE = Duration.ofMinutes(5);

    private static final class MutableClock extends Clock {
        Instant now = T0;

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

    private final MutableClock clock = new MutableClock();
    private final BlockStore store = new BlockStore(FIVE, Duration.ofHours(3), clock);

    private static Instant at(int minute) {
        return T0.plus(Duration.ofMinutes(minute));
    }

    private static Block block(int minute, boolean complete, MetricSeries... series) {
        return new Block(at(minute), at(minute + 5), List.of(series), List.of(), complete);
    }

    private static MetricSeries series() {
        return new MetricSeries(new String("srv-db-01"), "cpu", "1", "percent", 40.2, 31.0, 58.5, 5,
                Map.of("vlan", "10"));
    }

    @Test
    void betweenReturnsOnlyBlocksInsideRangeInOrder() {
        store.put(block(10, true));
        store.put(block(0, true));
        store.put(block(5, true));

        assertThat(store.window(at(0), at(10)).blocks()).extracting(Block::start).containsExactly(at(0), at(5));
    }

    @Test
    void blocksOlderThanRetentionArePruned() {
        store.put(block(0, true));
        clock.now = at(186);
        store.put(block(180, true));

        assertThat(store.get(at(0))).isEmpty();
        assertThat(store.get(at(180))).isPresent();
    }

    @Test
    void blockAtRetentionLimitIsKept() {
        store.put(block(0, true));
        clock.now = at(180);
        store.put(block(175, true));

        assertThat(store.get(at(0))).isPresent();
    }

    @Test
    void completeBlockReplacesIncompleteOne() {
        store.put(block(0, false));
        store.put(block(0, true, series()));

        assertThat(store.get(at(0))).hasValueSatisfying(b -> {
            assertThat(b.complete()).isTrue();
            assertThat(b.metrics()).hasSize(1);
        });
    }

    @Test
    void incompleteBlockDoesNotReplaceCompleteOne() {
        store.put(block(0, true, series()));
        store.put(block(0, false));

        assertThat(store.get(at(0))).hasValueSatisfying(b -> {
            assertThat(b.complete()).isTrue();
            assertThat(b.metrics()).hasSize(1);
        });
    }

    @Test
    void incompleteBlockDoesNotReplaceIncompleteOne() {
        store.put(block(0, false, series()));
        store.put(block(0, false));

        assertThat(store.get(at(0))).hasValueSatisfying(b -> assertThat(b.metrics()).hasSize(1));
    }

    @Test
    void windowBlocksAndCompletenessComeFromSameSnapshot() {
        store.put(block(0, true));
        BlockStore.Window window = store.window(at(0), at(10));

        store.put(block(5, true));

        assertThat(window.blocks()).hasSize(1);
        assertThat(window.complete()).isFalse();
    }

    @Test
    void completeNeedsAllBlocksAndAllComplete() {
        store.put(block(0, true));
        assertThat(store.window(at(0), at(10)).complete()).isFalse();

        store.put(block(5, false));
        assertThat(store.window(at(0), at(10)).complete()).isFalse();

        store.put(block(5, true));
        assertThat(store.window(at(0), at(10)).complete()).isTrue();
    }

    @Test
    void seriesAndEventsSurviveStorage() {
        MetricSeries empty = new MetricSeries("srv-db-02", "cpu", "", "percent", null, null, null, 0, Map.of());
        EventGroup event = new EventGroup("srv-db-01", "e", "", Severity.ERROR, "Major", 2,
                "2026-10-14T10:01:00Z", "2026-10-14T10:02:00Z", "giu'", Map.of());
        store.put(new Block(at(0), at(5), List.of(series(), empty), List.of(event), true));

        Block read = store.get(at(0)).orElseThrow();

        assertThat(read.metrics()).containsExactly(series(), empty);
        assertThat(read.events()).containsExactly(event);
        assertThat(read.end()).isEqualTo(at(5));
    }

    @Test
    void identicalSeriesShareIdentity() {
        store.put(block(0, true, series()));
        store.put(block(5, true, series()));

        MetricSeries first = store.get(at(0)).orElseThrow().metrics().get(0);
        MetricSeries second = store.get(at(5)).orElseThrow().metrics().get(0);

        assertThat(first.node()).isSameAs(second.node());
    }

    @Test
    void latestIsTheMostRecentBlock() {
        assertThat(store.latest()).isEmpty();

        store.put(block(5, true));
        store.put(block(0, true));

        assertThat(store.latest()).hasValueSatisfying(b -> assertThat(b.start()).isEqualTo(at(5)));
    }

    @Test
    void concurrentReadsDuringPutAreSafe() {
        AtomicBoolean done = new AtomicBoolean();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            while (!done.get()) {
                store.window(at(0), at(180)).blocks().forEach(b -> assertThat(b.metrics()).hasSize(1));
            }
        });

        for (int i = 0; i < 2_000; i++) {
            store.put(block(i % 36 * 5, true, series()));
        }
        done.set(true);

        reader.join();
    }
}
