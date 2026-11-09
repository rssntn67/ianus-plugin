package it.infocube.ianus.plugin.core.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * mancanti o incompleti entro la retention. Un blocco incompleto si rilegge al massimo una volta per
 * ciclo, cosi' l'NMS ha il tempo di completare i dati. Un blocco che fallisce con un errore imprevisto si
 * salta fino al ciclo successivo e si abbandona dopo 3 errori, cosi' non blocca il recupero dei piu' vecchi.
 * Non gestisce thread: lo usa un solo thread alla volta.
 */
public class SourceCollector {

    private static final Logger LOG = LoggerFactory.getLogger(SourceCollector.class);
    private static final int MAX_REREADS = 3;
    private static final int MAX_ERRORS = 3;

    private enum Outcome { SAVED, UNAVAILABLE, FAILED }

    private final SourceAdapter adapter;
    private final BlockStore store;
    private final Duration interval;
    private final Duration delay;
    private final Duration retention;
    private final Clock clock;
    private final Map<Instant, Integer> rereads = new HashMap<>();
    private final Set<Instant> readThisCycle = new HashSet<>();
    private final Map<Instant, Integer> errors = new HashMap<>();
    private final Set<Instant> failedThisCycle = new HashSet<>();
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
        readThisCycle.clear();
        failedThisCycle.clear();
        readThisCycle.add(due);
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
        errors.keySet().removeIf(t -> t.isBefore(oldest));
        for (Instant t = dueStart(now); !t.isBefore(oldest); t = t.minus(interval)) {
            if (failedThisCycle.contains(t) || errors.getOrDefault(t, 0) >= MAX_ERRORS) {
                continue;
            }
            Optional<Block> block = store.get(t);
            if (block.isEmpty()) {
                readThisCycle.add(t);
                return read(t) != Outcome.UNAVAILABLE;
            }
            if (!block.get().complete() && rereads.getOrDefault(t, 0) < MAX_REREADS && readThisCycle.add(t)) {
                Outcome outcome = read(t);
                if (outcome == Outcome.SAVED) {
                    rereads.merge(t, 1, Integer::sum);
                }
                return outcome != Outcome.UNAVAILABLE;
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

    private Outcome read(Instant start) {
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
            return Outcome.SAVED;
        } catch (SourceUnavailableException e) {
            LOG.warn("{}: blocco {} non letto: {}", adapter.system(), start, e.getMessage());
            return Outcome.UNAVAILABLE;
        } catch (RuntimeException e) {
            LOG.error("{}: errore imprevisto sul blocco {}", adapter.system(), start, e);
            failedThisCycle.add(start);
            if (errors.merge(start, 1, Integer::sum) == MAX_ERRORS) {
                LOG.warn("{}: blocco {} abbandonato dopo {} errori", adapter.system(), start, MAX_ERRORS);
            }
            return Outcome.FAILED;
        }
    }

    private void log(String kind, SourceResult<?> result, long begin) {
        LOG.info("{}: {} letti in {} ms ({} elementi, complete={})", adapter.system(), kind,
                (System.nanoTime() - begin) / 1_000_000, result.items().size(), result.complete());
    }
}
