package it.infocube.ianus.plugin.sources.opennms;

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

class OpenNmsAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));

    /** Tre attributi letti uno per lotto; il secondo lotto fallisce con l'errore indicato. */
    private static final class FailingClient extends OpenNmsClient {
        final RestClientException failure;
        int calls;

        FailingClient(OpenNmsProperties props, RestClientException failure) {
            super(props);
            this.failure = failure;
        }

        @Override
        public List<Map<String, Object>> nodes() {
            return List.of(Map.of("id", "1", "label", "srv-db-01"));
        }

        @Override
        public Map<String, Object> resourcesForNode(String nodeId) {
            return Map.of("id", "node[1].nodeSnmp[]",
                    "rrdGraphAttributes", Map.of("a", Map.of(), "b", Map.of(), "c", Map.of()));
        }

        @Override
        public Map<String, Object> measurements(Map<String, Object> request) {
            calls++;
            if (calls == 2) {
                throw failure;
            }
            return Map.of();
        }
    }

    private final OpenNmsProperties props = new OpenNmsProperties(true, null, "http://localhost:9", null, null,
            null, null, false, null, null, null, null, 1, null, null);

    @Test
    void connectionFailureStopsReadingBatches() {
        FailingClient client = new FailingClient(props, new ResourceAccessException("connessione rifiutata"));

        SourceResult<MetricSeries> result = new OpenNmsAdapter(props, client, Clock.systemUTC())
                .metrics(WINDOW, QueryFilter.NONE);

        assertThat(client.calls).isEqualTo(2);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).hasSize(3);
    }

    @Test
    void errorOnSingleBatchDoesNotStopTheOthers() {
        FailingClient client = new FailingClient(props, new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        SourceResult<MetricSeries> result = new OpenNmsAdapter(props, client, Clock.systemUTC())
                .metrics(WINDOW, QueryFilter.NONE);

        assertThat(client.calls).isEqualTo(3);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void eventConversion() {
        RawEvent e = OpenNmsAdapter.toRaw(Map.of(
                "uei", "uei.opennms.org/nodes/interfaceDown",
                "time", 1792130472000L,
                "severity", "MAJOR",
                "nodeLabel", "router-core-1",
                "ifIndex", 3,
                "logMessage", "Interface Gigabit0/1 is down"));
        assertThat(e.node()).isEqualTo("router-core-1");
        assertThat(e.instance()).isEqualTo("ifIndex=3");
        assertThat(e.severity()).isEqualTo(Severity.ERROR);
        assertThat(e.time()).isEqualTo(Instant.ofEpochMilli(1792130472000L));
    }
}
