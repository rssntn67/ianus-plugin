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

import it.infocube.ianus.plugin.core.aggregation.Aggregator;
import it.infocube.ianus.plugin.core.aggregation.EventAggregator;
import it.infocube.ianus.plugin.core.aggregation.MetricKind;
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
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.sources.zabbix.ZabbixClient.ZabbixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Plugin Zabbix. Metriche: item numerici (value_type 0 float, 3 intero) letti con history.get
 * nella finestra. Zabbix non distingue i contatori: quelli elencati in counter-keys diventano tassi,
 * a meno che un preprocessing "simple change" o "change per second" abbia gia' calcolato la variazione.
 * Eventi: event.get sui trigger (problemi e risoluzioni).
 */
@Component
@ConditionalOnProperty(prefix = "ianus.sources.zabbix", name = "enabled", havingValue = "true")
public class ZabbixAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ZabbixAdapter.class);
    static final String[] SEVERITIES = {"Not classified", "Information", "Warning", "Average", "High", "Disaster"};
    private static final Set<String> CHANGE_STEPS = Set.of("9", "10");

    /** I valori si dividono per {@code divisor} per passare all'unita' IANUS (millisecondi -> secondi). */
    record Item(String itemId, String node, String metric, String instance, String unit, int valueType,
                String name, MetricKind kind, double divisor) {
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
        batches:
        for (int valueType : new int[] {0, 3}) {
            for (MetricKind kind : MetricKind.values()) {
                List<String> ids = items.stream().filter(i -> i.valueType() == valueType && i.kind() == kind)
                        .map(Item::itemId).toList();
                // per i contatori serve anche il campione precedente alla finestra
                Instant timeFrom = kind == MetricKind.COUNTER ? window.start().minus(props.counterLookback())
                        : window.start();
                for (int from = 0; from < ids.size(); from += props.batchSize()) {
                    List<String> chunk = ids.subList(from, Math.min(from + props.batchSize(), ids.size()));
                    calls++;
                    try {
                        history(valueType, chunk, timeFrom, window, samples);
                    } catch (ResourceAccessException e) {
                        failures++;
                        last = e;
                        complete = false;
                        LOG.warn("Zabbix history.get: Zabbix non raggiungibile, lettura interrotta: {}",
                                e.getMessage());
                        break batches;
                    } catch (RestClientException | ZabbixException e) {
                        failures++;
                        last = e;
                        complete = false;
                        LOG.warn("Zabbix history.get: lotto di {} item non letto: {}", chunk.size(), e.getMessage());
                    }
                }
            }
        }
        if (calls > 0 && failures == calls) {
            throw new SourceUnavailableException(system(), last.getMessage());
        }
        List<MetricSeries> out = items.stream()
                .map(i -> MetricSeries.of(i.node(), i.metric(), i.instance(), i.unit(),
                        Aggregator.of(i.kind(), scaled(samples.getOrDefault(i.itemId(), List.of()), i.divisor()),
                                window),
                        Map.of("item", i.name() == null ? "" : i.name())))
                .toList();
        return new SourceResult<>(out, complete);
    }

    private static List<Sample> scaled(List<Sample> samples, double divisor) {
        return divisor == 1 ? samples : samples.stream().map(s -> new Sample(s.ts(), s.value() / divisor)).toList();
    }

    private void history(int valueType, List<String> itemIds, Instant timeFrom, TimeWindow window,
                         Map<String, List<Sample>> target) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("output", "extend");
        params.put("history", valueType);
        params.put("itemids", itemIds);
        params.put("time_from", timeFrom.getEpochSecond());
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
        } catch (ResourceAccessException e) {
            throw new SourceUnavailableException(system(), e.getMessage());
        } catch (RestClientException | ZabbixException e) {
            LOG.warn("Zabbix event.get: eventi non letti: {}", e.getMessage());
            return new SourceResult<>(List.of(), false);
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
        params.put("selectPreprocessing", List.of("type"));
        params.put("monitored", true);
        params.put("filter", Map.of("value_type", List.of(0, 3)));
        if (!props.hostGroupIds().isEmpty()) {
            params.put("groupids", props.hostGroupIds());
        }
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> it : Json.maps(client.call("item.get", params, true))) {
            Item item = toItem(it, props.counterKeys());
            if (item != null) {
                items.add(item);
            }
        }
        LOG.info("Inventario Zabbix: {} item numerici", items.size());
        return List.copyOf(items);
    }

    static Item toItem(Map<String, Object> it, List<String> counterKeys) {
        String id = Json.str(it.get("itemid"));
        String key = Json.str(it.get("key_"));
        Double vt = Json.num(it.get("value_type"));
        if (id == null || key == null || vt == null) {
            return null;
        }
        List<Map<String, Object>> hosts = Json.maps(it.get("hosts"));
        String host = hosts.isEmpty() ? "" : Json.str(hosts.get(0).get("host"), "");
        String[] parts = splitKey(key);
        boolean counter = counterKeys.contains(parts[0]) && !hasChangeStep(it);
        String units = Json.str(it.get("units"), "");
        String unit = unit(units);
        return new Item(id, host, Names.snake(parts[0]), parts[1], counter ? rateUnit(unit) : unit,
                vt.intValue(), Json.str(it.get("name")), counter ? MetricKind.COUNTER : MetricKind.GAUGE,
                "ms".equals(units) ? 1_000.0 : 1);
    }

    private static boolean hasChangeStep(Map<String, Object> it) {
        return Json.maps(it.get("preprocessing")).stream().anyMatch(p -> CHANGE_STEPS.contains(Json.str(p.get("type"))));
    }

    private static String rateUnit(String unit) {
        return "none".equals(unit) ? "per_second" : unit + "_per_second";
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
            case "ms" -> "seconds";
            default -> units.toLowerCase(Locale.ROOT);
        };
    }
}
