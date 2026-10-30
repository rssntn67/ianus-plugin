package it.infocube.ianus.plugin.sources.zabbix;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import it.infocube.ianus.plugin.core.source.SourceCapabilities;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.util.Json;
import it.infocube.ianus.plugin.core.util.Names;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.sources.zabbix.ZabbixClient.ZabbixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Plugin Zabbix. Metriche: item numerici (value_type 0 float, 3 intero) letti con history.get
 * nella finestra. Gli item con preprocessing "change per second" sono gia' tassi, quindi si
 * aggregano come gauge. Eventi: event.get sui trigger (problemi e risoluzioni).
 */
@Component
@ConditionalOnProperty(prefix = "ianus.sources.zabbix", name = "enabled", havingValue = "true")
public class ZabbixAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ZabbixAdapter.class);
    static final String[] SEVERITIES = {"Not classified", "Information", "Warning", "Average", "High", "Disaster"};

    record Item(String itemId, String node, String metric, String instance, String unit, int valueType,
                String name) {
    }

    record Inventory(List<Item> items, Instant loadedAt) {
    }

    private final ZabbixProperties props;
    private final ZabbixClient client;
    private final Clock clock;
    private volatile Inventory inventory;

    @Autowired
    public ZabbixAdapter(ZabbixProperties props, Clock clock) {
        this(props, new ZabbixClient(props), clock);
    }

    ZabbixAdapter(ZabbixProperties props, ZabbixClient client, Clock clock) {
        this.props = props;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String system() {
        return "zabbix";
    }

    @Override
    public String instanceId() {
        return props.id();
    }

    @Override
    public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
        List<Item> items = inventory().items().stream()
                .filter(i -> filter.nodeMatches(i.node()) && filter.metricMatches(i.metric()))
                .toList();
        Map<String, List<Sample>> samples = new HashMap<>();
        boolean complete = true;
        int calls = 0;
        int failures = 0;
        RuntimeException last = null;
        for (int valueType : new int[] {0, 3}) {
            List<String> ids = items.stream().filter(i -> i.valueType() == valueType).map(Item::itemId).toList();
            for (int from = 0; from < ids.size(); from += props.batchSize()) {
                List<String> chunk = ids.subList(from, Math.min(from + props.batchSize(), ids.size()));
                calls++;
                try {
                    history(valueType, chunk, window, samples);
                } catch (RestClientException | ZabbixException e) {
                    failures++;
                    last = e;
                    complete = false;
                    LOG.warn("Zabbix history.get: lotto di {} item non letto: {}", chunk.size(), e.getMessage());
                }
            }
        }
        if (calls > 0 && failures == calls) {
            throw new SourceUnavailableException(system(), last.getMessage());
        }
        List<MetricSeries> out = items.stream()
                .map(i -> MetricSeries.of(i.node(), i.metric(), i.instance(), i.unit(),
                        Aggregator.gauge(samples.getOrDefault(i.itemId(), List.of()), window),
                        Map.of("item", i.name() == null ? "" : i.name())))
                .toList();
        return new SourceResult<>(out, complete);
    }

    private void history(int valueType, List<String> itemIds, TimeWindow window, Map<String, List<Sample>> target) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("output", "extend");
        params.put("history", valueType);
        params.put("itemids", itemIds);
        params.put("time_from", window.start().getEpochSecond());
        params.put("time_till", window.end().getEpochSecond() - 1);
        params.put("sortfield", "clock");
        params.put("sortorder", "ASC");
        for (Map<String, Object> rec : Json.maps(client.call("history.get", params, true))) {
            String id = Json.str(rec.get("itemid"));
            Double clockSec = Json.num(rec.get("clock"));
            Double ns = Json.num(rec.get("ns"));
            Double value = Json.finite(rec.get("value"));
            if (id == null || clockSec == null || value == null) {
                continue;
            }
            Instant ts = Instant.ofEpochSecond(clockSec.longValue(), ns == null ? 0 : ns.longValue());
            target.computeIfAbsent(id, k -> new ArrayList<>()).add(new Sample(ts, value));
        }
    }

    @Override
    public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("output", List.of("eventid", "clock", "ns", "name", "severity", "value", "objectid"));
        params.put("selectHosts", List.of("host"));
        params.put("source", 0);
        params.put("object", 0);
        params.put("time_from", window.start().getEpochSecond());
        params.put("time_till", window.end().getEpochSecond() - 1);
        params.put("sortfield", List.of("clock"));
        params.put("sortorder", "ASC");
        params.put("limit", props.maxEvents());
        List<Map<String, Object>> events;
        try {
            events = Json.maps(client.call("event.get", params, true));
        } catch (RestClientException | ZabbixException e) {
            throw new SourceUnavailableException(system(), e.getMessage());
        }
        List<RawEvent> raw = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            RawEvent r = toRaw(ev);
            if (r != null) {
                raw.add(r);
            }
        }
        return new SourceResult<>(EventAggregator.group(raw, window), events.size() < props.maxEvents());
    }

    static RawEvent toRaw(Map<String, Object> ev) {
        Double clockSec = Json.num(ev.get("clock"));
        String objectId = Json.str(ev.get("objectid"));
        if (clockSec == null || objectId == null) {
            return null;
        }
        Double ns = Json.num(ev.get("ns"));
        Instant time = Instant.ofEpochSecond(clockSec.longValue(), ns == null ? 0 : ns.longValue());
        Double sev = Json.num(ev.get("severity"));
        int s = sev == null ? 0 : sev.intValue();
        String nativeSeverity = s >= 0 && s < SEVERITIES.length ? SEVERITIES[s] : String.valueOf(s);
        List<Map<String, Object>> hosts = Json.maps(ev.get("hosts"));
        String host = hosts.isEmpty() ? "" : Json.str(hosts.get(0).get("host"), "");
        String state = "1".equals(Json.str(ev.get("value"))) ? "problem" : "ok";
        return new RawEvent(host, "zabbix.trigger." + objectId, "",
                SeverityMapper.map("zabbix", nativeSeverity), nativeSeverity, time,
                Json.str(ev.get("name")), Map.of("value", state));
    }

    @Override
    public SourceCapabilities capabilities() {
        Set<String> metrics = new TreeSet<>();
        Set<String> nodes = new TreeSet<>();
        inventory().items().forEach(i -> {
            metrics.add(i.metric());
            nodes.add(i.node());
        });
        return new SourceCapabilities(List.copyOf(metrics), List.copyOf(nodes), props.historyDepth());
    }

    @Override
    public SourceHealth health() {
        try {
            Object version = client.call("apiinfo.version", List.of(), false);
            return new SourceHealth(system(), instanceId(), true, "Zabbix API " + version);
        } catch (RestClientException | ZabbixException e) {
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
                inv = new Inventory(discover(), clock.instant());
                inventory = inv;
                return inv;
            } catch (RestClientException | ZabbixException e) {
                if (inv != null) {
                    LOG.warn("Rilettura degli item Zabbix fallita, uso l'elenco precedente: {}", e.getMessage());
                    return inv;
                }
                throw new SourceUnavailableException(system(), e.getMessage());
            }
        }
    }

    private List<Item> discover() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("output", List.of("itemid", "name", "key_", "units", "value_type"));
        params.put("selectHosts", List.of("hostid", "host"));
        params.put("monitored", true);
        params.put("filter", Map.of("value_type", List.of(0, 3)));
        if (!props.hostGroupIds().isEmpty()) {
            params.put("groupids", props.hostGroupIds());
        }
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> it : Json.maps(client.call("item.get", params, true))) {
            Item item = toItem(it);
            if (item != null) {
                items.add(item);
            }
        }
        LOG.info("Inventario Zabbix: {} item numerici", items.size());
        return List.copyOf(items);
    }

    static Item toItem(Map<String, Object> it) {
        String id = Json.str(it.get("itemid"));
        String key = Json.str(it.get("key_"));
        Double vt = Json.num(it.get("value_type"));
        if (id == null || key == null || vt == null) {
            return null;
        }
        List<Map<String, Object>> hosts = Json.maps(it.get("hosts"));
        String host = hosts.isEmpty() ? "" : Json.str(hosts.get(0).get("host"), "");
        String[] parts = splitKey(key);
        return new Item(id, host, Names.snake(parts[0]), parts[1], unit(Json.str(it.get("units"), "")),
                vt.intValue(), Json.str(it.get("name")));
    }

    /** system.cpu.util[,user] -> {"system.cpu.util", ",user"}. */
    static String[] splitKey(String key) {
        int b = key.indexOf('[');
        if (b < 0) {
            return new String[] {key, ""};
        }
        String params = key.endsWith("]") ? key.substring(b + 1, key.length() - 1) : key.substring(b + 1);
        return new String[] {key.substring(0, b), params};
    }

    static String unit(String units) {
        return switch (units) {
            case "" -> "none";
            case "%" -> "percent";
            case "B" -> "bytes";
            case "Bps" -> "bytes_per_second";
            case "bps" -> "bits_per_second";
            case "s", "uptime" -> "seconds";
            case "ms" -> "milliseconds";
            default -> units.toLowerCase(Locale.ROOT);
        };
    }
}
