package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.severity.SeverityMapper;
import org.junit.jupiter.api.Test;

class SeverityMapperTest {

    @Test
    void mapsAccordingToSpecificationTable() {
        assertThat(SeverityMapper.map("opennms", "Cleared")).isEqualTo(Severity.INFO);
        assertThat(SeverityMapper.map("opennms", "MINOR")).isEqualTo(Severity.WARNING);
        assertThat(SeverityMapper.map("opennms", "Major")).isEqualTo(Severity.ERROR);
        assertThat(SeverityMapper.map("opennms", "Critical")).isEqualTo(Severity.CRITICAL);
        assertThat(SeverityMapper.map("zabbix", "Average")).isEqualTo(Severity.ERROR);
        assertThat(SeverityMapper.map("zabbix", "Disaster")).isEqualTo(Severity.CRITICAL);
        assertThat(SeverityMapper.map("icinga", "UNKNOWN")).isEqualTo(Severity.WARNING);
        assertThat(SeverityMapper.map("icinga", "DOWN")).isEqualTo(Severity.CRITICAL);
        assertThat(SeverityMapper.map("prometheus", "none")).isEqualTo(Severity.INFO);
        assertThat(SeverityMapper.map("prometheus", "")).isEqualTo(Severity.WARNING);
        assertThat(SeverityMapper.map("syslog", "3")).isEqualTo(Severity.ERROR);
    }

    @Test
    void unknownSeverityBecomesWarning() {
        assertThat(SeverityMapper.map("opennms", "Bizarre")).isEqualTo(Severity.WARNING);
    }
}
