package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.aggregation.EventAggregator;
import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import org.junit.jupiter.api.Test;

class EventAggregatorTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void groupsByKeyWithCountsAndLastMessage() {
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(300), Duration.ofMinutes(5));
        List<EventGroup> groups = EventAggregator.group(List.of(
                event(T0.plusSeconds(72), "down 1"),
                event(T0.plusSeconds(288), "down 3"),
                event(T0.plusSeconds(150), "down 2"),
                event(T0.plusSeconds(400), "fuori finestra")), w);
        assertThat(groups).hasSize(1);
        EventGroup g = groups.get(0);
        assertThat(g.count()).isEqualTo(3);
        assertThat(g.firstSeen()).isEqualTo("2026-10-14T10:01:12Z");
        assertThat(g.lastSeen()).isEqualTo("2026-10-14T10:04:48Z");
        assertThat(g.message()).isEqualTo("down 3");
        assertThat(g.nativeSeverity()).isEqualTo("Major");
    }

    private static RawEvent event(Instant t, String message) {
        return new RawEvent("router-core-1", "uei.opennms.org/nodes/interfaceDown", "Gigabit0/1",
                Severity.ERROR, "Major", t, message, Map.of());
    }
}
