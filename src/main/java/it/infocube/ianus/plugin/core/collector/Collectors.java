package it.infocube.ianus.plugin.core.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.config.CollectorProperties;
import it.infocube.ianus.plugin.config.CollectorProperties.CollectSettings;
import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Crea archivio e Collector di ogni sorgente e li fa girare su un thread per sorgente, cosi' un NMS
 * lento non ferma gli altri. All'avvio rifiuta le configurazioni con cui le finestre non si
 * potrebbero costruire dai blocchi.
 */
@Component
public class Collectors implements SmartLifecycle {

    private final Map<String, BlockStore> stores = new LinkedHashMap<>();
    private final Map<String, SourceCollector> collectors = new LinkedHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Clock clock;
    private volatile boolean running;

    public Collectors(ObjectProvider<SourceAdapter> adapters, CollectorProperties settings, IanusProperties properties,
                      WindowResolver resolver, Clock clock) {
        this.clock = clock;
        List<Duration> windows = resolver.supported();
        Duration largest = Collections.max(windows);
        if (properties.retention().compareTo(largest) < 0) {
            throw new IllegalStateException("ianus.retention (" + properties.retention()
                    + ") deve coprire la finestra piu' ampia (" + largest + ")");
        }
        adapters.orderedStream().forEach(adapter -> {
            CollectSettings s = settings.settings(adapter.system());
            for (Duration window : windows) {
                if (window.toMillis() % s.collectInterval().toMillis() != 0) {
                    throw new IllegalStateException("collect-interval di " + adapter.system() + " ("
                            + s.collectInterval() + ") deve dividere la finestra " + window);
                }
            }
            BlockStore store = new BlockStore(s.collectInterval(), properties.retention(), clock);
            stores.put(adapter.system(), store);
            collectors.put(adapter.system(),
                    new SourceCollector(adapter, store, s.collectDelay(), properties.retention(), clock));
        });
    }

    public BlockStore store(String system) {
        return stores.get(system);
    }

    @Override
    public void start() {
        running = true;
        collectors.forEach((system, collector) -> threads.add(Thread.ofPlatform().daemon()
                .name("collector-" + system).start(() -> loop(collector))));
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop(SourceCollector collector) {
        try {
            while (running) {
                collector.collectDue();
                boolean recovering = true;
                while (running && recovering && clock.instant().isBefore(collector.nextRun())) {
                    recovering = collector.recoverOne();
                }
                Instant next = collector.nextRun();
                Thread.sleep(Duration.between(clock.instant(), next));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
