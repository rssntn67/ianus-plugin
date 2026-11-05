package it.infocube.ianus.plugin.sources.icinga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.config.IanusProperties;
import it.infocube.ianus.plugin.core.error.SourceUnavailableException;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.source.QueryFilter;
import it.infocube.ianus.plugin.core.source.SourceResult;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** Il plugin parte alle 10:00; ogni test simula poll riusciti e falliti in momenti precisi. */
class IcingaAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeClient extends IcingaClient {
        final MutableClock clock;
        boolean down;

        FakeClient(IcingaProperties props, MutableClock clock) {
            super(props);
            this.clock = clock;
        }

        @Override
        public List<Map<String, Object>> hosts() {
            if (down) {
                throw new ResourceAccessException("connessione rifiutata");
            }
            return List.of(Map.of("attrs", Map.of("name", "srv-db-01", "state", 0, "last_state_change", 1,
                    "last_check_result", Map.of("execution_end", clock.now.getEpochSecond(),
                            "performance_data", List.of("load=1.5")))));
        }

        @Override
        public List<Map<String, Object>> services() {
            return List.of();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final IcingaProperties props = new IcingaProperties(true, null, "http://localhost:9", null, null,
            null, null, false, null, Duration.ofHours(1));
    private final FakeClient client = new FakeClient(props, clock);
    private final IcingaAdapter adapter = new IcingaAdapter(props, client, clock);

    private void pollAt(int minute, boolean ok) {
        clock.now = T0.plus(Duration.ofMinutes(minute));
        client.down = !ok;
        adapter.poll();
    }

    /** Finestra di 5 minuti che inizia al minuto indicato rispetto alle 10:00. */
    private static TimeWindow window(int startMinute) {
        Instant start = T0.plus(Duration.ofMinutes(startMinute));
        return new TimeWindow(start, start.plus(Duration.ofMinutes(5)), Duration.ofMinutes(5));
    }

    @Test
    void windowWithoutOutageIsComplete() {
        pollAt(0, true);
        pollAt(5, true);
        pollAt(9, true);
        pollAt(10, true);

        assertThat(adapter.metrics(window(5), QueryFilter.NONE).complete()).isTrue();
        assertThat(adapter.events(window(5), QueryFilter.NONE).complete()).isTrue();
    }

    @Test
    void windowPartlyInsideOutageIsIncompleteAndKeepsCollectedData() {
        pollAt(5, true);
        pollAt(6, true);
        pollAt(7, false);
        pollAt(8, true);
        clock.now = T0.plus(Duration.ofMinutes(11));

        SourceResult<MetricSeries> result = adapter.metrics(window(5), QueryFilter.NONE);

        assertThat(result.complete()).isFalse();
        assertThat(result.items()).singleElement().satisfies(s -> assertThat(s.samples()).isEqualTo(3));
        assertThat(adapter.events(window(5), QueryFilter.NONE).complete()).isFalse();
    }

    @Test
    void ongoingOutageMakesRecentWindowIncomplete() {
        pollAt(7, true);
        pollAt(11, false);

        assertThat(adapter.metrics(window(5), QueryFilter.NONE).complete()).isFalse();
    }

    @Test
    void windowEntirelyInsideOutageIsUnavailable() {
        pollAt(4, true);
        pollAt(5, false);
        pollAt(11, false);

        assertThatThrownBy(() -> adapter.metrics(window(5), QueryFilter.NONE))
                .isInstanceOf(SourceUnavailableException.class);
        assertThatThrownBy(() -> adapter.events(window(5), QueryFilter.NONE))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void windowsAroundClosedOutageAreComplete() {
        pollAt(4, true);
        pollAt(5, true);
        pollAt(6, false);
        pollAt(9, true);
        pollAt(10, true);
        pollAt(15, true);

        assertThat(adapter.metrics(window(0), QueryFilter.NONE).complete()).isTrue();
        assertThat(adapter.metrics(window(5), QueryFilter.NONE).complete()).isFalse();
        assertThat(adapter.metrics(window(10), QueryFilter.NONE).complete()).isTrue();
    }

    @Test
    void failuresBeforeFirstSuccessCountFromStartup() {
        pollAt(1, false);
        pollAt(7, true);
        clock.now = T0.plus(Duration.ofMinutes(11));

        assertThatThrownBy(() -> adapter.metrics(window(0), QueryFilter.NONE))
                .isInstanceOf(SourceUnavailableException.class);
        assertThat(adapter.metrics(window(5), QueryFilter.NONE).complete()).isFalse();
    }

    @Test
    void windowBeforeStartupIsIncompleteNotUnavailable() {
        pollAt(1, true);

        assertThat(adapter.metrics(window(-5), QueryFilter.NONE).complete()).isFalse();
    }

    @Test
    void retentionShorterThanGlobalOneRefusesToStart() {
        IanusProperties global = new IanusProperties("k", null, null, null, null, Duration.ofHours(3));

        assertThatThrownBy(() -> new IcingaAdapter(props, global, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
    }

    @Test
    void outageOlderThanRetentionIsForgotten() {
        pollAt(4, true);
        pollAt(5, false);
        pollAt(11, true);
        pollAt(90, true);

        assertThat(adapter.metrics(window(5), QueryFilter.NONE).complete()).isTrue();
    }
}
