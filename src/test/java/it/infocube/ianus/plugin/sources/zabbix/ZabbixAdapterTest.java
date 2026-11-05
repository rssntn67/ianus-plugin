package it.infocube.ianus.plugin.sources.zabbix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
            null, null, false, null, null, null, 1, null);

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

    @Test
    void itemKeyBecomesMetricAndInstance() {
        ZabbixAdapter.Item item = ZabbixAdapter.toItem(Map.of(
                "itemid", "23296", "key_", "system.cpu.util[,user]", "units", "%", "value_type", "0",
                "name", "CPU user time", "hosts", List.of(Map.of("hostid", "10084", "host", "srv-db-01"))));
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
