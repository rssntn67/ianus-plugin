package it.infocube.ianus.plugin.sources.icinga;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.aggregation.EventAggregator;
import it.infocube.ianus.plugin.core.aggregation.Sample;
import it.infocube.ianus.plugin.core.buffer.EventBuffer;
import it.infocube.ianus.plugin.core.buffer.SampleBuffer;
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
import it.infocube.ianus.plugin.sources.icinga.PerfdataParser.PerfValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Plugin Icinga 2. Interroga periodicamente /v1/objects/hosts e /v1/objects/services:
 * <ul>
 *   <li>metriche: performance data dell'ultimo controllo (nuovo campione quando cambia execution_end);
 *       metric = etichetta del perfdata, instance = nome del servizio;</li>
 *   <li>eventi: cambi di stato rilevati da last_state_change.</li>
 * </ul>
 * I dati restano in memoria per la retention configurata.
 */
@Component
@ConditionalOnProperty(prefix = "ianus.sources.icinga", name = "enabled", havingValue = "true")
public class IcingaAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(IcingaAdapter.class);
    private static final String[] SERVICE_STATES = {"OK", "WARNING", "CRITICAL", "UNKNOWN"};
    private static final String[] HOST_STATES = {"UP", "DOWN"};

    /** Periodo in cui Icinga non ha risposto: dall'ultimo poll riuscito al successivo. */
    private record Outage(Instant start, Instant end) {

        boolean overlaps(TimeWindow window) {
            return window.start().isBefore(end) && start.isBefore(window.end());
        }

        boolean covers(TimeWindow window) {
            return !window.start().isBefore(start) && !window.end().isAfter(end);
        }
    }

    private final IcingaProperties props;
    private final IcingaClient client;
    private final Clock clock;
    private final SampleBuffer samples = new SampleBuffer();
    private final EventBuffer events = new EventBuffer();
    private final Map<String, Double> lastExecution = new ConcurrentHashMap<>();
    private final Map<String, Double> lastStateChange = new ConcurrentHashMap<>();
    private final Instant startedAt;
    private final List<Outage> outages = new CopyOnWriteArrayList<>();
    private volatile Instant lastSuccessfulPoll;
    private volatile Instant outageStart;

    @Autowired
    public IcingaAdapter(IcingaProperties props, IanusProperties ianus, Clock clock) {
        this(checked(props, ianus), new IcingaClient(props), clock);
    }

    /** Il Collector rilegge dal buffer: deve conservare almeno la storia del plugin. */
    private static IcingaProperties checked(IcingaProperties props, IanusProperties ianus) {
        if (props.retention().compareTo(ianus.retention()) < 0) {
            throw new IllegalStateException("ianus.sources.icinga.retention (" + props.retention()
                    + ") deve essere almeno pari a ianus.retention (" + ianus.retention() + ")");
        }
        return props;
    }

    IcingaAdapter(IcingaProperties props, IcingaClient client, Clock clock) {
        this.props = props;
        this.client = client;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Override
    public String system() {
        return "icinga";
    }

    @Override
    public String instanceId() {
        return props.id();
    }

    @Scheduled(fixedDelayString = "${ianus.sources.icinga.poll-interval:PT30S}", initialDelayString = "PT5S")
    public void poll() {
        try {
            client.hosts().forEach(h -> process(h, true));
            client.services().forEach(s -> process(s, false));
            Instant now = clock.instant();
            if (outageStart != null) {
                outages.add(new Outage(outageStart, now));
                outageStart = null;
            }
            lastSuccessfulPoll = now;
        } catch (RestClientException e) {
            LOG.warn("Icinga non raggiungibile: {}", e.getMessage());
            if (outageStart == null) {
                outageStart = lastSuccessfulPoll == null ? startedAt : lastSuccessfulPoll;
            }
        }
        Instant cutoff = clock.instant().minus(props.retention());
        samples.prune(cutoff);
        events.prune(cutoff);
        outages.removeIf(o -> o.end().isBefore(cutoff));
    }

    void process(Map<String, Object> object, boolean host) {
        Map<String, Object> attrs = Json.map(object.get("attrs"));
        String hostName = Json.str(attrs.get(host ? "name" : "host_name"));
        if (hostName == null) {
            return;
        }
        String service = host ? "" : Json.str(attrs.get("name"), "");
        String key = host ? hostName : hostName + "!" + service;
        Map<String, Object> result = Json.map(attrs.get("last_check_result"));

        Double executionEnd = Json.num(result.get("execution_end"));
        if (executionEnd != null) {
            Double previous = lastExecution.put(key, executionEnd);
            if (previous == null || executionEnd > previous) {
                Instant ts = Times.fromEpochSeconds(executionEnd);
                for (Object entry : Json.list(result.get("performance_data"))) {
                    for (PerfValue v : PerfdataParser.parseEntry(entry)) {
                        samples.add(new SampleBuffer.SeriesKey(hostName, Names.snake(v.label()), service),
                                v.unit(), v.kind(), Map.of(), new Sample(ts, v.value()));
                    }
                }
            }
        }

        Double change = Json.num(attrs.get("last_state_change"));
        Double state = Json.num(attrs.get("state"));
        if (change != null && state != null) {
            Double previous = lastStateChange.put(key, change);
            if (previous != null && change > previous) {
                String stateName = stateName(host, state.intValue());
                events.add(new RawEvent(hostName,
                        host ? "icinga.host.state_change" : "icinga.service.state_change",
                        service, SeverityMapper.map(system(), stateName), stateName,
                        Times.fromEpochSeconds(change), Json.str(result.get("output")), Map.of()));
            }
        }
    }

    /** Uno stato fuori scala resta il numero nativo, cosi' diventa WARNING e viene segnalato (sezione 6.1). */
    static String stateName(boolean host, int state) {
        String[] names = host ? HOST_STATES : SERVICE_STATES;
        return state >= 0 && state < names.length ? names[state] : String.valueOf(state);
    }

    @Override
    public SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter) {
        requireData(window);
        return new SourceResult<>(samples.aggregate(window, filter), covers(window));
    }

    @Override
    public SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter) {
        requireData(window);
        return new SourceResult<>(EventAggregator.group(events.in(window), window), covers(window));
    }

    /** Completa se il plugin raccoglieva gia' al suo inizio, Icinga ha risposto e un poll ne ha visto la fine. */
    private boolean covers(TimeWindow window) {
        return !window.start().isBefore(startedAt) && !lastSuccessfulPoll.isBefore(window.end())
                && allOutages().noneMatch(o -> o.overlaps(window));
    }

    private void requireData(TimeWindow window) {
        if (lastSuccessfulPoll == null) {
            throw new SourceUnavailableException(system(), "nessuna interrogazione di Icinga riuscita finora");
        }
        if (allOutages().anyMatch(o -> o.covers(window))) {
            throw new SourceUnavailableException(system(), "Icinga non ha risposto per tutta la finestra");
        }
    }

    /** Interruzioni concluse piu' quella eventualmente in corso. */
    private Stream<Outage> allOutages() {
        Instant open = outageStart;
        return open == null ? outages.stream()
                : Stream.concat(outages.stream(), Stream.of(new Outage(open, clock.instant())));
    }

    @Override
    public SourceHealth health() {
        try {
            client.status();
            return new SourceHealth(system(), instanceId(), true, "OK");
        } catch (RestClientException e) {
            return new SourceHealth(system(), instanceId(), false, e.getMessage());
        }
    }
}
