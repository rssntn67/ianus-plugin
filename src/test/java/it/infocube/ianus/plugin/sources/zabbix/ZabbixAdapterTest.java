package it.infocube.ianus.plugin.sources.zabbix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class ZabbixAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));

    /** Due item float e uno intero, letti uno per lotto; il secondo lotto fallisce con l'errore indicato. */
    private static final class FailingClient extends ZabbixClient {
        final RestClientException failure;
        int historyCalls;

        FailingClient(ZabbixProperties props, RestClientException failure) {
            super(props);
            this.failure = failure;
        }

        @Override
        public Object call(String method, Object params, boolean authenticated) {
            if ("item.get".equals(method)) {
                return List.of(item("1", "a", "0"), item("2", "b", "0"), item("3", "c", "3"));
            }
            historyCalls++;
            if (historyCalls == 2) {
                throw failure;
            }
            return List.of();
        }

        private static Map<String, Object> item(String id, String key, String valueType) {
            return Map.of("itemid", id, "key_", key, "value_type", valueType,
                    "hosts", List.of(Map.of("host", "srv-db-01")));
        }
    }

    private final ZabbixProperties props = new ZabbixProperties(true, null, "http://localhost:9", null, null,
            null, null, false, null, null, 1, null, null, null);

    /** Risponde come l'API: preprocessing solo se richiesto, campioni solo da time_from in poi. */
    private static final class CounterClient extends ZabbixClient {

        CounterClient(ZabbixProperties props) {
            super(props);
        }

        @Override
        public Object call(String method, Object params, boolean authenticated) {
            Map<?, ?> p = (Map<?, ?>) params;
            if ("item.get".equals(method)) {
                boolean withSteps = p.containsKey("selectPreprocessing");
                return List.of(item("1", "net.if.in[eth0]", "B", List.of(), withSteps),
                        item("2", "net.if.out[eth0]", "Bps", List.of("10"), withSteps),
                        item("3", "net.if.out[eth1]", "", List.of("9"), withSteps),
                        item("4", "net.if.in[eth1]", "", List.of(), withSteps),
                        item("5", "system.cpu.load", "", List.of(), withSteps),
                        item("6", "net.if.in[slow]", "B", List.of(), withSteps),
                        item("7", "icmppingsec", "ms", List.of(), withSteps));
            }
            long from = ((Number) p.get("time_from")).longValue();
            String id = ((List<?>) p.get("itemids")).get(0).toString();
            return samples(id).stream()
                    .filter(s -> Long.parseLong(s.get("clock").toString()) >= from)
                    .toList();
        }

        private static List<Map<String, Object>> samples(String id) {
            return switch (id) {
                case "1" -> List.of(sample(id, -60, "1000"), sample(id, 60, "1600"), sample(id, 120, "100"),
                        sample(id, 180, "160"));
                case "2" -> List.of(sample(id, 60, "10"), sample(id, 120, "20"));
                case "6" -> List.of(sample(id, -540, "1000"), sample(id, 60, "7000"));
                case "7" -> List.of(sample(id, 60, "250"), sample(id, 120, "350"));
                default -> List.of();
            };
        }

        private static Map<String, Object> sample(String id, int offset, String value) {
            return Map.of("itemid", id, "clock", String.valueOf(T0.getEpochSecond() + offset), "ns", "0",
                    "value", value);
        }

        private static Map<String, Object> item(String id, String key, String units, List<String> steps,
                                                boolean withSteps) {
            Map<String, Object> item = new java.util.HashMap<>(Map.of("itemid", id, "key_", key, "units", units,
                    "value_type", "3", "hosts", List.of(Map.of("host", "router-1"))));
            if (withSteps) {
                item.put("preprocessing", steps.stream().map(t -> Map.of("type", t)).toList());
            }
            return item;
        }
    }

    private final ZabbixProperties counterProps = new ZabbixProperties(true, null, "http://localhost:9", null, null,
            null, null, false, null, null, 1, null, List.of("net.if.in", "net.if.out"), null);

    private MetricSeries series(String metric, String instance) {
        return new ZabbixAdapter(counterProps, new CounterClient(counterProps), Clock.systemUTC())
                .metrics(WINDOW, QueryFilter.NONE).items().stream()
                .filter(s -> s.metric().equals(metric) && s.instance().equals(instance))
                .findFirst().orElseThrow();
    }

    @Test
    void listedCounterBecomesRateUsingSampleBeforeWindowAndDroppingReset() {
        MetricSeries s = series("net_if_in", "eth0");

        assertThat(s.value()).isEqualTo(3.0);
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.max()).isEqualTo(5.0);
        assertThat(s.samples()).isEqualTo(2);
        assertThat(s.unit()).isEqualTo("bytes_per_second");
    }

    @Test
    void counterSlowerThanBlockFindsItsPreviousSample() {
        MetricSeries s = series("net_if_in", "slow");

        assertThat(s.value()).isEqualTo(10.0);
        assertThat(s.samples()).isEqualTo(1);
    }

    @Test
    void counterWithoutUnitIsPerSecond() {
        assertThat(series("net_if_in", "eth1").unit()).isEqualTo("per_second");
    }

    @Test
    void listedItemWithChangePerSecondStaysGauge() {
        MetricSeries s = series("net_if_out", "eth0");

        assertThat(s.value()).isEqualTo(15.0);
        assertThat(s.samples()).isEqualTo(2);
        assertThat(s.unit()).isEqualTo("bytes_per_second");
    }

    @Test
    void listedItemWithSimpleChangeStaysGauge() {
        assertThat(series("net_if_out", "eth1").unit()).isEqualTo("none");
    }

    @Test
    void millisecondsBecomeSeconds() {
        MetricSeries s = series("icmppingsec", "");

        assertThat(s.unit()).isEqualTo("seconds");
        assertThat(s.value()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.min()).isEqualTo(0.25);
        assertThat(s.max()).isEqualTo(0.35);
    }

    @Test
    void itemNotListedStaysGauge() {
        assertThat(series("system_cpu_load", "").unit()).isEqualTo("none");
    }

    @Test
    void connectionFailureStopsReadingBatches() {
        FailingClient client = new FailingClient(props, new ResourceAccessException("connessione rifiutata"));

        SourceResult<MetricSeries> result = new ZabbixAdapter(props, client, Clock.systemUTC())
                .metrics(WINDOW, QueryFilter.NONE);

        assertThat(client.historyCalls).isEqualTo(2);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).hasSize(3);
    }

    @Test
    void errorOnSingleBatchDoesNotStopTheOthers() {
        FailingClient client = new FailingClient(props, new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        SourceResult<MetricSeries> result = new ZabbixAdapter(props, client, Clock.systemUTC())
                .metrics(WINDOW, QueryFilter.NONE);

        assertThat(client.historyCalls).isEqualTo(3);
        assertThat(result.complete()).isFalse();
    }

    private ZabbixAdapter failingEvents(RuntimeException failure) {
        return new ZabbixAdapter(props, new ZabbixClient(props) {
            @Override
            public Object call(String method, Object params, boolean authenticated) {
                throw failure;
            }
        }, Clock.systemUTC());
    }

    @Test
    void httpOrApiErrorOnEventsGivesIncompleteEvents() {
        for (RuntimeException failure : List.of(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR),
                new ZabbixClient.ZabbixException("event.get: Session terminated"))) {
            SourceResult<EventGroup> result = failingEvents(failure).events(WINDOW, QueryFilter.NONE);

            assertThat(result.items()).isEmpty();
            assertThat(result.complete()).isFalse();
        }
    }

    @Test
    void unreachableOnEventsIsUnavailable() {
        assertThatThrownBy(() -> failingEvents(new ResourceAccessException("connessione rifiutata"))
                .events(WINDOW, QueryFilter.NONE))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void itemKeyBecomesMetricAndInstance() {
        ZabbixAdapter.Item item = ZabbixAdapter.toItem(Map.of(
                "itemid", "23296", "key_", "system.cpu.util[,user]", "units", "%", "value_type", "0",
                "name", "CPU user time", "hosts", List.of(Map.of("hostid", "10084", "host", "srv-db-01"))), List.of());
        assertThat(item.metric()).isEqualTo("system_cpu_util");
        assertThat(item.instance()).isEqualTo(",user");
        assertThat(item.unit()).isEqualTo("percent");
        assertThat(item.node()).isEqualTo("srv-db-01");
        assertThat(item.valueType()).isZero();
    }

    @Test
    void eventSeverityIsMapped() {
        RawEvent e = ZabbixAdapter.toRaw(Map.of("eventid", "1", "clock", "1792130472", "ns", "0",
                "name", "High CPU", "severity", "4", "value", "1", "objectid", "13491",
                "hosts", List.of(Map.of("host", "srv-db-01"))));
        assertThat(e.eventId()).isEqualTo("zabbix.trigger.13491");
        assertThat(e.nativeSeverity()).isEqualTo("High");
        assertThat(e.severity()).isEqualTo(Severity.ERROR);
        assertThat(e.labels()).containsEntry("value", "problem");
    }
}
