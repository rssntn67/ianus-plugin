package it.infocube.ianus.plugin.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.collector.Block;
import it.infocube.ianus.plugin.core.collector.BlockStore;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Misura manuale della memoria: 3 ore di blocchi da 5 minuti con 50.000 serie. */
@Disabled("misura manuale")
class BlockStoreMemoryTest {

    private static final int SERIES = 50_000;
    private static final int BLOCKS = 36;

    @Test
    void measureMemory() {
        Instant start = Instant.parse("2026-10-14T10:00:00Z");
        Duration five = Duration.ofMinutes(5);
        Clock clock = Clock.fixed(start.plus(five.multipliedBy(BLOCKS)), ZoneOffset.UTC);
        BlockStore store = new BlockStore(five, Duration.ofHours(4), clock);
        long before = used();

        for (int b = 0; b < BLOCKS; b++) {
            Instant blockStart = start.plus(five.multipliedBy(b));
            List<MetricSeries> series = new ArrayList<>(SERIES);
            for (int i = 0; i < SERIES; i++) {
                series.add(new MetricSeries("router-core-" + i / 100, "if_hc_in_octets_" + i % 10,
                        "Gigabit0/" + i % 100, "bytes_per_second", 1.5 * i, 1.0 * i, 2.0 * i, 5,
                        Map.of("resource_id", "node[" + i / 100 + "].interfaceSnmp[Gi0-" + i % 100 + "]")));
            }
            store.put(new Block(blockStart, blockStart.plus(five), series, List.of(), true));
        }

        System.out.printf("MEMORIA: %d MB per %d serie e %d blocchi%n", (used() - before) / 1_048_576, SERIES,
                store.window(start, start.plus(five.multipliedBy(BLOCKS))).blocks().size());
    }

    private static long used() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
