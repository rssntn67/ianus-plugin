package it.infocube.ianus.plugin.core.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.error.BadRequestException;
import it.infocube.ianus.plugin.core.error.SourceNotFoundException;
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
import it.infocube.ianus.plugin.core.source.SourceCapabilities;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Logica comune a tutte le sorgenti: finestra, filtri, cache, paginazione, busta della risposta. */
@Service
public class IanusService {

    private static final Logger LOG = LoggerFactory.getLogger(IanusService.class);

    static final Comparator<MetricSeries> METRIC_ORDER = Comparator.comparing(MetricSeries::node)
            .thenComparing(MetricSeries::metric)
            .thenComparing(MetricSeries::instance)
            .thenComparing(s -> s.labels().toString());

    private final Map<String, SourceAdapter> adapters;
    private final WindowResolver resolver;
    private final ResultCache cache;
    private final IanusProperties properties;

    public IanusService(ObjectProvider<SourceAdapter> adapters, WindowResolver resolver, ResultCache cache,
                        IanusProperties properties) {
        Map<String, SourceAdapter> m = new LinkedHashMap<>();
        adapters.orderedStream().forEach(a -> m.put(a.system(), a));
        this.adapters = Collections.unmodifiableMap(m);
        this.resolver = resolver;
        this.cache = cache;
        this.properties = properties;
    }

    public MetricsResponse metrics(String source, String window, String end, List<String> nodes,
                                   List<String> metrics, Integer page, Integer size) {
        SourceAdapter adapter = adapter(source);
        TimeWindow w = resolver.resolve(window, end);
        QueryFilter filter = QueryFilter.of(nodes, metrics);
        Paging paging = paging(page, size);
        SourceResult<MetricSeries> result = cache.get(key("metrics", adapter, w, filter),
                () -> timed(adapter, "metrics", () -> adapter.metrics(w, filter)), SourceResult::complete);
        List<MetricSeries> items = result.items().stream()
                .filter(s -> filter.nodeMatches(s.node()) && filter.metricMatches(s.metric()))
                .sorted(METRIC_ORDER)
                .toList();
        return new MetricsResponse(info(adapter), w.toInfo(), result.complete(),
                new PageInfo(paging.number(), paging.size(), items.size()), slice(items, paging));
    }

    public EventsResponse events(String source, String window, String end, List<String> nodes,
                                 Integer page, Integer size) {
        SourceAdapter adapter = adapter(source);
        TimeWindow w = resolver.resolve(window, end);
        QueryFilter filter = QueryFilter.of(nodes, null);
        Paging paging = paging(page, size);
        SourceResult<EventGroup> result = cache.get(key("events", adapter, w, filter),
                () -> timed(adapter, "events", () -> adapter.events(w, filter)), SourceResult::complete);
        List<EventGroup> items = result.items().stream()
                .filter(e -> filter.nodeMatches(e.node()))
                .sorted(Comparator.comparing(EventGroup::node).thenComparing(EventGroup::eventId)
                        .thenComparing(EventGroup::instance).thenComparing(EventGroup::severity))
                .toList();
        return new EventsResponse(info(adapter), w.toInfo(), result.complete(),
                new PageInfo(paging.number(), paging.size(), items.size()), slice(items, paging));
    }

    public CapabilitiesResponse capabilities(String source) {
        SourceAdapter adapter = adapter(source);
        SourceCapabilities c = adapter.capabilities();
        return new CapabilitiesResponse(info(adapter),
                resolver.supported().stream().map(Duration::toString).toList(),
                List.copyOf(new TreeSet<>(c.metrics())),
                List.copyOf(new TreeSet<>(c.nodes())),
                c.historyDepth() == null ? null : c.historyDepth().toString());
    }

    public HealthResponse health() {
        List<SourceHealth> sources = new ArrayList<>();
        for (SourceAdapter a : adapters.values()) {
            try {
                sources.add(a.health());
            } catch (RuntimeException e) {
                sources.add(new SourceHealth(a.system(), a.instanceId(), false, e.getMessage()));
            }
        }
        boolean allUp = sources.stream().allMatch(SourceHealth::reachable);
        return new HealthResponse(allUp ? "UP" : "DEGRADED", properties.pluginVersion(), sources);
    }

    /** Tempo di risposta dell'NMS (sezione 8): solo sulle letture reali, non su quelle dalla cache. */
    private static <T> SourceResult<T> timed(SourceAdapter adapter, String kind, Supplier<SourceResult<T>> call) {
        long start = System.nanoTime();
        SourceResult<T> result = call.get();
        LOG.info("{}: {} letti in {} ms ({} elementi, complete={})", adapter.system(), kind,
                (System.nanoTime() - start) / 1_000_000, result.items().size(), result.complete());
        return result;
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

    private static String key(String kind, SourceAdapter a, TimeWindow w, QueryFilter f) {
        return kind + '|' + a.system() + '|' + w.start() + '|' + w.end() + '|'
                + new TreeSet<>(f.nodes()) + '|' + new TreeSet<>(f.metrics());
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
