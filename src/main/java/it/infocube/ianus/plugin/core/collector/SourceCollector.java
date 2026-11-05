package it.infocube.ianus.plugin.core.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ciclo di raccolta di una sorgente: legge il blocco dovuto e, nel tempo libero, recupera quelli
 * mancanti o incompleti entro la retention. Non gestisce thread: lo usa un solo thread alla volta.
 */
public class SourceCollector {

    private static final Logger LOG = LoggerFactory.getLogger(SourceCollector.class);
    private static final int MAX_REREADS = 3;

    private final SourceAdapter adapter;
    private final BlockStore store;
    private final Duration interval;
    private final Duration delay;
    private final Duration retention;
    private final Clock clock;
    private final Map<Instant, Integer> rereads = new HashMap<>();
    private Instant lastDue;

    public SourceCollector(SourceAdapter adapter, BlockStore store, Duration delay, Duration retention, Clock clock) {
        this.adapter = adapter;
        this.store = store;
        this.interval = store.interval();
        this.delay = delay;
        this.retention = retention;
        this.clock = clock;
    }

    /** Legge il blocco dovuto, se non e' gia' stato letto. */
    public void collectDue() {
        Instant due = dueStart(clock.instant());
        if (due.equals(lastDue)) {
            return;
        }
        lastDue = due;
        long start = System.nanoTime();
        read(due);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (elapsed.compareTo(interval) > 0) {
            LOG.warn("{}: la raccolta del blocco {} e' durata {}, piu' dell'intervallo {}", adapter.system(), due,
                    elapsed, interval);
        }
    }

    /** Legge il blocco mancante o incompleto piu' recente; false se non c'e' nulla da fare o l'NMS non risponde. */
    public boolean recoverOne() {
        Instant now = clock.instant();
        Instant oldest = now.minus(retention);
        rereads.keySet().removeIf(t -> t.isBefore(oldest));
        for (Instant t = dueStart(now); !t.isBefore(oldest); t = t.minus(interval)) {
            Optional<Block> block = store.get(t);
            if (block.isEmpty()) {
                return read(t);
            }
            if (!block.get().complete() && rereads.getOrDefault(t, 0) < MAX_REREADS) {
                rereads.merge(t, 1, Integer::sum);
                return read(t);
            }
        }
        return false;
    }

    /** Fine del blocco successivo a quello dovuto, piu' l'attesa. */
    public Instant nextRun() {
        return dueStart(clock.instant()).plus(interval).plus(interval).plus(delay);
    }

    /** Inizio dell'ultimo blocco con fine + delay <= now. */
    private Instant dueStart(Instant now) {
        long step = interval.toMillis();
        long limit = now.minus(delay).toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(limit, step) * step).minus(interval);
    }

    private boolean read(Instant start) {
        TimeWindow window = new TimeWindow(start, start.plus(interval), interval);
        try {
            long begin = System.nanoTime();
            SourceResult<MetricSeries> metrics = adapter.metrics(window, QueryFilter.NONE);
            log("metrics", metrics, begin);
            begin = System.nanoTime();
            SourceResult<EventGroup> events = adapter.events(window, QueryFilter.NONE);
            log("events", events, begin);
            store.put(new Block(window.start(), window.end(), metrics.items(), events.items(),
                    metrics.complete() && events.complete()));
            return true;
        } catch (SourceUnavailableException e) {
            LOG.warn("{}: blocco {} non letto: {}", adapter.system(), start, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("{}: errore imprevisto sul blocco {}", adapter.system(), start, e);
        }
        return false;
    }

    private void log(String kind, SourceResult<?> result, long begin) {
        LOG.info("{}: {} letti in {} ms ({} elementi, complete={})", adapter.system(), kind,
                (System.nanoTime() - begin) / 1_000_000, result.items().size(), result.complete());
    }
}
