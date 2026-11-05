package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.EventsResponse;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.MetricsResponse;
import it.infocube.ianus.plugin.core.model.PageInfo;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.model.SourceInfo;
import it.infocube.ianus.plugin.core.model.WindowInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Serializzazione JSON della busta v2 (sezione 7) con il JsonMapper configurato da Spring Boot. */
@SpringBootTest(properties = "ianus.api-key=test-key")
class JsonSerializationTest {

    private static final SourceInfo SOURCE = new SourceInfo("opennms", "opennms-01", "2.0.0");
    private static final WindowInfo WINDOW = new WindowInfo("2026-10-14T10:00:00Z", "2026-10-14T10:05:00Z", "PT5M");

    @Autowired
    JsonMapper mapper;

    @Test
    void metricsEnvelopeUsesSnakeCaseAndKeepsNullStats() {
        MetricSeries empty = new MetricSeries("srv-db-01", "cpu_usage", "1", "percent", null, null, null, 0, Map.of());
        MetricSeries full = new MetricSeries("srv-db-01", "if_in_traffic", "eth0", "bytes_per_second",
                40.2, 31.0, 58.5, 5, Map.of("device", "eth0"));
        JsonNode json = mapper.valueToTree(new MetricsResponse(SOURCE, WINDOW, true,
                new PageInfo(0, 500, 2), List.of(empty, full)));

        assertThat(json.path("source").path("plugin_version").asString()).isEqualTo("2.0.0");
        assertThat(json.path("source").has("pluginVersion")).isFalse();
        assertThat(json.path("page").path("total_elements").asLong()).isEqualTo(2);
        assertThat(json.path("page").has("totalElements")).isFalse();
        assertThat(json.path("window").path("start").asString()).isEqualTo("2026-10-14T10:00:00Z");

        JsonNode e = json.path("metrics").get(0);
        assertThat(e.has("value")).isTrue();
        assertThat(e.get("value").isNull()).isTrue();
        assertThat(e.get("min").isNull()).isTrue();
        assertThat(e.get("max").isNull()).isTrue();
        assertThat(e.path("samples").asLong()).isZero();
        assertThat(e.has("labels")).isFalse();

        JsonNode f = json.path("metrics").get(1);
        assertThat(f.path("value").isNumber()).isTrue();
        assertThat(f.path("value").asDouble()).isEqualTo(40.2);
        assertThat(f.path("labels").path("device").asString()).isEqualTo("eth0");
    }

    @Test
    void lastCollectedIsSnakeCaseAndOmittedWhenAbsent() {
        SourceHealth health = new SourceHealth("opennms", "opennms-01", true, "OK");

        assertThat(mapper.valueToTree(health).has("last_collected")).isFalse();
        JsonNode json = mapper.valueToTree(health.withLastCollected("2026-10-14T10:05:00Z"));
        assertThat(json.path("last_collected").asString()).isEqualTo("2026-10-14T10:05:00Z");
        assertThat(json.has("lastCollected")).isFalse();
    }

    @Test
    void eventsEnvelopeUsesSnakeCaseAndOmitsEmptyLabels() {
        EventGroup g = new EventGroup("srv-db-01", "uei.opennms.org/nodes/nodeDown", "", Severity.CRITICAL,
                "Major", 3, "2026-10-14T10:01:12Z", "2026-10-14T10:04:40Z", null, Map.of());
        JsonNode json = mapper.valueToTree(new EventsResponse(SOURCE, WINDOW, false,
                new PageInfo(0, 500, 1), List.of(g)));

        assertThat(json.path("complete").asBoolean()).isFalse();
        JsonNode e = json.path("events").get(0);
        assertThat(e.path("event_id").asString()).isEqualTo("uei.opennms.org/nodes/nodeDown");
        assertThat(e.path("native_severity").asString()).isEqualTo("Major");
        assertThat(e.path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(e.path("first_seen").asString()).isEqualTo("2026-10-14T10:01:12Z");
        assertThat(e.path("last_seen").asString()).isEqualTo("2026-10-14T10:04:40Z");
        assertThat(e.path("count").isIntegralNumber()).isTrue();
        assertThat(e.has("labels")).isFalse();
        assertThat(e.has("message")).isFalse();
        assertThat(e.has("eventId")).isFalse();
        assertThat(e.has("continued")).isFalse();
        assertThat(e.has("firstSeen")).isFalse();
    }
}
