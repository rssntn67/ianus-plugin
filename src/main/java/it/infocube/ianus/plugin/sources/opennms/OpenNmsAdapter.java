package it.infocube.ianus.plugin.sources.opennms;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.infocube.ianus.plugin.core.aggregation.Aggregator;
import it.infocube.ianus.plugin.core.aggregation.EventAggregator;
import it.infocube.ianus.plugin.core.aggregation.Sample;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.severity.SeverityMapper;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceAdapter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.util.Json;
import it.infocube.ianus.plugin.core.util.Names;
import it.infocube.ianus.plugin.core.util.Times;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Plugin OpenNMS. Metriche: Measurements API sui grafici RRD/JRB/Newts dei nodi selezionati.
 * I DS di tipo COUNTER sono gia' restituiti da OpenNMS come tassi, quindi si aggregano come gauge.
 * Eventi: API v2 /api/v2/events filtrata per eventTime nella finestra.
 */
@Component
@ConditionalOnProperty(prefix = "ianus.sources.opennms", name = "enabled", havingValue = "true")
public class OpenNmsAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(OpenNmsAdapter.class);
    private static final int EVENT_PAGE = 1000;

    record SeriesRef(String node, String resourceId, String attribute, String metric, String instance, String unit) {
    }

    record Inventory(List<SeriesRef> series, Instant loadedAt) {
    }

    private final OpenNmsProperties props;
    private final OpenNmsClient client;
    private final Clock clock;
    private final Map<String, String> units = new HashMap<>();
    private volatile Inventory inventory;

    @Autowired
    public OpenNmsAdapter(OpenNmsProperties props, Clock clock) {
        this(props, new OpenNmsClient(props), clock);
    }

    OpenNmsAdapter(OpenNmsProperties props, OpenNmsClient client, Clock clock) {
        this.props = props;
        this.client = client;
        this.clock = clock;
        props.units().forEach(u -> units.put(u.attribute(), u.unit()));
    }

    @Override
    public String system() {
        return "opennms";
    }

    @Override
    public String instanceId() {
        return props.id();
    }

    @Override
    public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
        List<SeriesRef> refs = inventory().series().stream()
                .filter(r -> filter.nodeMatches(r.node()) && filter.metricMatches(r.metric()))
                .toList();
        Map<SeriesRef, List<Sample>> samples = new HashMap<>();
        int batches = 0;
        int failures = 0;
        RestClientException last = null;
        for (int i = 0; i < refs.size(); i += props.batchSize()) {
            List<SeriesRef> chunk = refs.subList(i, Math.min(i + props.batchSize(), refs.size()));
            batches++;
            try {
                fetch(chunk, window, samples);
            } catch (ResourceAccessException e) {
                failures++;
                last = e;
                LOG.warn("Measurements API: OpenNMS non raggiungibile, lettura interrotta: {}", e.getMessage());
                break;
            } catch (RestClientException e) {
                failures++;
                last = e;
                LOG.warn("Measurements API: lotto di {} attributi non letto: {}", chunk.size(), e.getMessage());
            }
        }
        if (batches > 0 && failures == batches) {
            throw new SourceUnavailableException(system(), last.getMessage());
        }
        List<MetricSeries> out = refs.stream()
                .map(r -> MetricSeries.of(r.node(), r.metric(), r.instance(), r.unit(),
                        Aggregator.gauge(samples.getOrDefault(r, List.of()), window),
                        Map.of("resource_id", r.resourceId())))
                .toList();
        return new SourceResult<>(out, failures == 0);
    }

    private void fetch(List<SeriesRef> chunk, TimeWindow window, Map<SeriesRef, List<Sample>> target) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int j = 0; j < chunk.size(); j++) {
            SeriesRef r = chunk.get(j);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("label", "s" + j);
            s.put("resourceId", r.resourceId());
            s.put("attribute", r.attribute());
            s.put("aggregation", "AVERAGE");
            s.put("transient", false);
            sources.add(s);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("start", window.start().toEpochMilli());
        request.put("end", window.end().toEpochMilli());
        request.put("step", props.sampleStep().toMillis());
        request.put("relaxed", true);
        request.put("source", sources);

        Map<String, Object> response = client.measurements(request);
        List<Object> timestamps = Json.list(response.get("timestamps"));
        List<Object> labels = Json.list(response.get("labels"));
        List<Map<String, Object>> columns = Json.maps(response.get("columns"));
        for (int c = 0; c < columns.size() && c < labels.size(); c++) {
            String label = Json.str(labels.get(c));
            if (label == null || !label.startsWith("s")) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(label.substring(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= chunk.size()) {
                continue;
            }
            List<Sample> list = target.computeIfAbsent(chunk.get(idx), k -> new ArrayList<>());
            List<Object> values = Json.list(columns.get(c).get("values"));
            for (int k = 0; k < values.size() && k < timestamps.size(); k++) {
                Double v = Json.finite(values.get(k));
                Double t = Json.num(timestamps.get(k));
                if (v != null && t != null) {
                    list.add(new Sample(Instant.ofEpochMilli(t.longValue()), v));
                }
            }
        }
    }

    @Override
    public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
        String fiql = "eventTime=ge=" + Times.opennms(window.start()) + ";eventTime=lt=" + Times.opennms(window.end());
        List<RawEvent> raw = new ArrayList<>();
        boolean complete = true;
        int offset = 0;
        int pages = 0;
        while (true) {
            Map<String, Object> response;
            try {
                response = client.events(fiql, offset, EVENT_PAGE);
            } catch (RestClientException e) {
                if (pages == 0) {
                    throw new SourceUnavailableException(system(), e.getMessage());
                }
                LOG.warn("Eventi OpenNMS: pagina {} non letta: {}", pages, e.getMessage());
                complete = false;
                break;
            }
            List<Map<String, Object>> page = Json.maps(response.get("event"));
            page.forEach(ev -> {
                RawEvent r = toRaw(ev);
                if (r != null) {
                    raw.add(r);
                }
            });
            pages++;
            offset += page.size();
            Double total = Json.num(response.get("totalCount"));
            if (page.isEmpty() || (total != null && offset >= total)) {
                break;
            }
            if (pages >= props.maxEventPages()) {
                complete = false;
                break;
            }
        }
        return new SourceResult<>(EventAggregator.group(raw, window), complete);
    }

    static RawEvent toRaw(Map<String, Object> ev) {
        Object timeValue = ev.containsKey("time") ? ev.get("time") : ev.get("eventTime");
        Instant time = Times.parseFlexible(timeValue != null ? timeValue : ev.get("createTime"));
        String uei = Json.str(ev.get("uei"));
        if (time == null || uei == null) {
            return null;
        }
        String node = Json.str(ev.get("nodeLabel"), Json.str(ev.get("host"), Json.str(ev.get("ipAddress"), "")));
        String instance = Json.str(Json.map(ev.get("serviceType")).get("name"), "");
        if (instance.isEmpty() && ev.get("ifIndex") != null) {
            instance = "ifIndex=" + Json.str(ev.get("ifIndex"));
        }
        String nativeSeverity = Json.str(ev.get("severity"), "");
        Map<String, String> labels = new HashMap<>();
        if (ev.get("ipAddress") != null) {
            labels.put("ip_address", Json.str(ev.get("ipAddress")));
        }
        return new RawEvent(node, uei, instance, SeverityMapper.map("opennms", nativeSeverity), nativeSeverity,
                time, Json.str(ev.get("logMessage")), labels);
    }

    @Override
    public SourceHealth health() {
        try {
            client.info();
            return new SourceHealth(system(), instanceId(), true, "OK");
        } catch (RestClientException e) {
            return new SourceHealth(system(), instanceId(), false, e.getMessage());
        }
    }

    private Inventory inventory() {
        Inventory inv = inventory;
        if (inv != null && inv.loadedAt().plus(props.discoveryRefresh()).isAfter(clock.instant())) {
            return inv;
        }
        synchronized (this) {
            inv = inventory;
            if (inv != null && inv.loadedAt().plus(props.discoveryRefresh()).isAfter(clock.instant())) {
                return inv;
            }
            try {
                inv = discover();
                inventory = inv;
                return inv;
            } catch (RestClientException e) {
                if (inv != null) {
                    LOG.warn("Rilettura dell'inventario OpenNMS fallita, uso quello precedente: {}", e.getMessage());
                    return inv;
                }
                throw new SourceUnavailableException(system(), e.getMessage());
            }
        }
    }

    Inventory discover() {
        Set<String> wanted = Set.copyOf(props.nodes());
        int nodes = 0;
        List<SeriesRef> refs = new ArrayList<>();
        for (Map<String, Object> node : client.nodes()) {
            String id = Json.str(node.get("id"));
            String label = Json.str(node.get("label"));
            if (id == null || label == null || (!wanted.isEmpty() && !wanted.contains(label))) {
                continue;
            }
            nodes++;
            collect(client.resourcesForNode(id), label, refs);
        }
        LOG.info("Inventario OpenNMS: {} nodi, {} serie", nodes, refs.size());
        return new Inventory(List.copyOf(refs), clock.instant());
    }

    private void collect(Map<String, Object> resource, String node, List<SeriesRef> refs) {
        String resourceId = Json.str(resource.get("id"));
        if (resourceId != null) {
            String instance = isNodeLevel(resourceId) ? ""
                    : Json.str(resource.get("label"), Json.str(resource.get("name"), resourceId));
            for (String attribute : attributeNames(resource.get("rrdGraphAttributes"))) {
                refs.add(new SeriesRef(node, resourceId, attribute, Names.snake(attribute), instance,
                        units.getOrDefault(attribute, "unknown")));
            }
        }
        Object children = resource.get("children");
        List<Map<String, Object>> kids = children instanceof Map<?, ?>
                ? Json.maps(Json.map(children).get("resource"))
                : Json.maps(children);
        kids.forEach(kid -> collect(kid, node, refs));
    }

    private static boolean isNodeLevel(String resourceId) {
        return !resourceId.contains(".") || resourceId.endsWith(".nodeSnmp[]");
    }

    private static List<String> attributeNames(Object attributes) {
        if (attributes instanceof Map<?, ?> m) {
            return m.keySet().stream().map(String::valueOf).toList();
        }
        return Json.maps(attributes).stream().map(a -> Json.str(a.get("name"))).filter(n -> n != null).toList();
    }
}
