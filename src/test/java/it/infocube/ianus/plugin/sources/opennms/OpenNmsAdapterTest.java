package it.infocube.ianus.plugin.sources.opennms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.Severity;
import org.junit.jupiter.api.Test;

class OpenNmsAdapterTest {

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
