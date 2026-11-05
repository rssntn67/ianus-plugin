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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(Collectors.class);
    private static final Duration ERROR_PAUSE = Duration.ofSeconds(1);

    private final Map<String, BlockStore> stores = new LinkedHashMap<>();
    private final Map<String, SourceCollector> collectors = new LinkedHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Clock clock;
    private volatile boolean running;

    public Collectors(ObjectProvider<SourceAdapter> adapters, CollectorProperties settings, IanusProperties properties,
                      WindowResolver resolver, Clock clock) {
        this.clock = clock;
        List<Duration> windows = resolver.supported();
        adapters.orderedStream().forEach(adapter -> {
            CollectSettings s = settings.settings(adapter.system());
            validate(adapter.system(), s, windows, properties.retention());
            BlockStore store = new BlockStore(s.collectInterval(), properties.retention(), clock);
            stores.put(adapter.system(), store);
            collectors.put(adapter.system(),
                    new SourceCollector(adapter, store, s.collectDelay(), properties.retention(), clock));
        });
    }

    private static void validate(String system, CollectSettings s, List<Duration> windows, Duration retention) {
        if (s.collectInterval().toMillis() <= 0) {
            throw new IllegalStateException("collect-interval di " + system + " deve essere positivo: "
                    + s.collectInterval());
        }
        if (s.collectDelay().isNegative()) {
            throw new IllegalStateException("collect-delay di " + system + " non puo' essere negativo: "
                    + s.collectDelay());
        }
        for (Duration window : windows) {
            if (window.toMillis() % s.collectInterval().toMillis() != 0) {
                throw new IllegalStateException("collect-interval di " + system + " (" + s.collectInterval()
                        + ") deve dividere la finestra " + window);
            }
        }
        // una finestra si puo' chiedere solo dopo la raccolta del suo ultimo blocco
        Duration needed = Collections.max(windows).plus(s.collectInterval()).plus(s.collectDelay());
        if (retention.compareTo(needed) < 0) {
            throw new IllegalStateException("ianus.retention (" + retention + ") deve essere almeno " + needed
                    + " per " + system + ": finestra piu' ampia, collect-interval e collect-delay");
        }
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
        while (running) {
            try {
                cycle(collector);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // anche un OutOfMemoryError: la sorgente non deve fermarsi fino al riavvio
                LOG.error("Ciclo di raccolta interrotto da un errore", t);
                pause();
            }
        }
    }

    private void cycle(SourceCollector collector) throws InterruptedException {
        collector.collectDue();
        Instant next = collector.nextRun();
        boolean recovering = true;
        while (running && recovering && clock.instant().isBefore(next)) {
            recovering = collector.recoverOne();
        }
        Thread.sleep(Duration.between(clock.instant(), next));
    }

    private static void pause() {
        try {
            Thread.sleep(ERROR_PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
