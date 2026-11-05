package it.infocube.ianus.plugin.core.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;

/**
 * Blocchi raccolti da una sorgente, conservati in memoria per la retention. Ogni identita' di serie
 * e' memorizzata una sola volta: i blocchi tengono il riferimento e i numeri, cosi' decine di
 * migliaia di serie restano sostenibili. Scrive un solo thread, leggono quelli delle richieste.
 */
public class BlockStore {

    private record Identity(String node, String metric, String instance, String unit, Map<String, String> labels) {
    }

    /** value, min e max valgono NaN dove la serie ha null. */
    private record Point(Identity identity, double value, double min, double max, long samples) {

        static Point of(Identity identity, MetricSeries s) {
            return new Point(identity, number(s.value()), number(s.min()), number(s.max()), s.samples());
        }

        MetricSeries toSeries() {
            return new MetricSeries(identity.node(), identity.metric(), identity.instance(), identity.unit(),
                    nullable(value), nullable(min), nullable(max), samples, identity.labels());
        }

        private static double number(Double d) {
            return d == null ? Double.NaN : d;
        }

        private static Double nullable(double d) {
            return Double.isNaN(d) ? null : d;
        }
    }

    private record Stored(Instant start, Instant end, List<Point> points, List<EventGroup> events, boolean complete) {

        Block toBlock() {
            return new Block(start, end, points.stream().map(Point::toSeries).toList(), events, complete);
        }
    }

    private final ConcurrentNavigableMap<Instant, Stored> blocks = new ConcurrentSkipListMap<>();
    private final Map<Identity, Identity> identities = new HashMap<>();
    private final Duration interval;
    private final Duration retention;
    private final Clock clock;

    public BlockStore(Duration interval, Duration retention, Clock clock) {
        this.interval = interval;
        this.retention = retention;
        this.clock = clock;
    }

    public Duration interval() {
        return interval;
    }

    /** Salva il blocco; uno completo non viene sostituito da uno incompleto. */
    public synchronized void put(Block block) {
        Stored existing = blocks.get(block.start());
        if (existing == null || !existing.complete() || block.complete()) {
            blocks.put(block.start(), store(block));
        }
        prune();
    }

    public Optional<Block> get(Instant start) {
        return Optional.ofNullable(blocks.get(start)).map(Stored::toBlock);
    }

    /** Blocchi con inizio in [start, end), in ordine crescente. */
    public List<Block> between(Instant start, Instant end) {
        return blocks.subMap(start, true, end, false).values().stream().map(Stored::toBlock).toList();
    }

    /** Vero se [start, end) ha tutti i blocchi attesi e sono tutti completi. */
    public boolean complete(Instant start, Instant end) {
        var found = blocks.subMap(start, true, end, false).values();
        return found.size() == Duration.between(start, end).dividedBy(interval)
                && found.stream().allMatch(Stored::complete);
    }

    public Optional<Block> latest() {
        return Optional.ofNullable(blocks.lastEntry()).map(e -> e.getValue().toBlock());
    }

    private Stored store(Block block) {
        List<Point> points = block.metrics().stream().map(s -> {
            Identity identity = new Identity(s.node(), s.metric(), s.instance(), s.unit(), s.labels());
            return Point.of(identities.computeIfAbsent(identity, k -> k), s);
        }).toList();
        return new Stored(block.start(), block.end(), points, block.events(), block.complete());
    }

    private void prune() {
        var expired = blocks.headMap(clock.instant().minus(retention), false);
        if (expired.isEmpty()) {
            return;
        }
        expired.clear();
        Set<Identity> used = new HashSet<>();
        blocks.values().forEach(b -> b.points().forEach(p -> used.add(p.identity())));
        identities.keySet().retainAll(used);
    }
}
