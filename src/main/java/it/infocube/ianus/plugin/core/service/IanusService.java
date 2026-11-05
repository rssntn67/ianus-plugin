package it.infocube.ianus.plugin.core.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.aggregation.Consolidator;
import it.infocube.ianus.plugin.core.collector.BlockStore;
import it.infocube.ianus.plugin.core.collector.Collectors;
import it.infocube.ianus.plugin.core.error.BadRequestException;
import it.infocube.ianus.plugin.core.error.SourceNotFoundException;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.CapabilitiesResponse;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.EventsResponse;
import it.infocube.ianus.plugin.core.model.HealthResponse;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.MetricsResponse;
import it.infocube.ianus.plugin.core.model.PageInfo;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.model.SourceInfo;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.util.Times;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Logica comune a tutte le sorgenti: finestra, consolidamento dei blocchi, filtri, paginazione, busta. */
@Service
public class IanusService {

    static final Comparator<MetricSeries> METRIC_ORDER = Comparator.comparing(MetricSeries::node)
            .thenComparing(MetricSeries::metric)
            .thenComparing(MetricSeries::instance)
            .thenComparing(s -> s.labels().toString());

    private final Map<String, SourceAdapter> adapters;
    private final WindowResolver resolver;
    private final Collectors collectors;
    private final IanusProperties properties;
    private final Clock clock;

    public IanusService(ObjectProvider<SourceAdapter> adapters, WindowResolver resolver, Collectors collectors,
                        IanusProperties properties, Clock clock) {
        Map<String, SourceAdapter> m = new LinkedHashMap<>();
        adapters.orderedStream().forEach(a -> m.put(a.system(), a));
        this.adapters = Collections.unmodifiableMap(m);
        this.resolver = resolver;
        this.collectors = collectors;
        this.properties = properties;
        this.clock = clock;
    }

    public MetricsResponse metrics(String source, String window, String end, List<String> nodes,
                                   List<String> metrics, Integer page, Integer size) {
        SourceAdapter adapter = adapter(source);
        TimeWindow w = resolver.resolve(window, end);
        QueryFilter filter = QueryFilter.of(nodes, metrics);
        Paging paging = paging(page, size);
        BlockStore.Window blocks = blocks(adapter, w);
        List<MetricSeries> raw = blocks.blocks().stream().flatMap(b -> b.metrics().stream())
                .filter(s -> filter.nodeMatches(s.node()) && filter.metricMatches(s.metric()))
                .toList();
        List<MetricSeries> items = Consolidator.metrics(raw).stream().sorted(METRIC_ORDER).toList();
        return new MetricsResponse(info(adapter), w.toInfo(), blocks.complete(),
                new PageInfo(paging.number(), paging.size(), items.size()), slice(items, paging));
    }

    public EventsResponse events(String source, String window, String end, List<String> nodes,
                                 Integer page, Integer size) {
        SourceAdapter adapter = adapter(source);
        TimeWindow w = resolver.resolve(window, end);
        QueryFilter filter = QueryFilter.of(nodes, null);
        Paging paging = paging(page, size);
        BlockStore.Window blocks = blocks(adapter, w);
        List<EventGroup> raw = blocks.blocks().stream().flatMap(b -> b.events().stream())
                .filter(e -> filter.nodeMatches(e.node()))
                .toList();
        List<EventGroup> items = Consolidator.events(raw);
        return new EventsResponse(info(adapter), w.toInfo(), blocks.complete(),
                new PageInfo(paging.number(), paging.size(), items.size()), slice(items, paging));
    }

    public CapabilitiesResponse capabilities(String source) {
        SourceAdapter adapter = adapter(source);
        TreeSet<String> metrics = new TreeSet<>();
        TreeSet<String> nodes = new TreeSet<>();
        collectors.store(adapter.system()).latest().ifPresent(b -> {
            b.metrics().forEach(s -> {
                metrics.add(s.metric());
                nodes.add(s.node());
            });
            b.events().forEach(e -> nodes.add(e.node()));
        });
        return new CapabilitiesResponse(info(adapter),
                resolver.supported().stream().map(Duration::toString).toList(),
                List.copyOf(metrics), List.copyOf(nodes), properties.retention().toString());
    }

    public HealthResponse health() {
        List<SourceHealth> sources = new ArrayList<>();
        for (SourceAdapter a : adapters.values()) {
            SourceHealth health;
            try {
                health = a.health();
            } catch (RuntimeException e) {
                health = new SourceHealth(a.system(), a.instanceId(), false, e.getMessage());
            }
            sources.add(health.withLastCollected(collectors.store(a.system()).latest()
                    .map(b -> Times.format(b.end())).orElse(null)));
        }
        boolean allUp = sources.stream().allMatch(SourceHealth::reachable);
        return new HealthResponse(allUp ? "UP" : "DEGRADED", properties.pluginVersion(), sources);
    }

    /** Blocchi raccolti della finestra: le richieste di IANUS non interrogano mai l'NMS. */
    private BlockStore.Window blocks(SourceAdapter adapter, TimeWindow w) {
        if (w.start().isBefore(clock.instant().minus(properties.retention()))) {
            throw new BadRequestException("window_too_old",
                    "La finestra e' piu' vecchia della storia conservata: " + properties.retention());
        }
        BlockStore.Window blocks = collectors.store(adapter.system()).window(w.start(), w.end());
        if (blocks.blocks().isEmpty()) {
            throw SourceUnavailableException.noBlocks(adapter.system());
        }
        return blocks;
    }

    private SourceAdapter adapter(String source) {
        SourceAdapter a = adapters.get(source);
        if (a == null) {
            throw new SourceNotFoundException(source);
        }
        return a;
    }

    private SourceInfo info(SourceAdapter a) {
        return new SourceInfo(a.system(), a.instanceId(), properties.pluginVersion());
    }

    private record Paging(int number, int size) {
    }

    private Paging paging(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int s = size == null ? properties.defaultPageSize() : size;
        if (number < 0) {
            throw new BadRequestException("invalid_page", "page deve essere >= 0");
        }
        if (s < 1 || s > properties.maxPageSize()) {
            throw new BadRequestException("invalid_size", "size deve essere tra 1 e " + properties.maxPageSize());
        }
        return new Paging(number, s);
    }

    private static <T> List<T> slice(List<T> items, Paging p) {
        long from = (long) p.number() * p.size();
        if (from >= items.size()) {
            return List.of();
        }
        int to = (int) Math.min(items.size(), from + p.size());
        return items.subList((int) from, to);
    }
}
