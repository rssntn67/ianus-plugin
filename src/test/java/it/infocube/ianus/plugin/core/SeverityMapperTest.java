package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import it.infocube.ianus.plugin.core.model.Severity;
import it.infocube.ianus.plugin.core.severity.SeverityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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

    @ParameterizedTest
    @CsvSource({
            "opennms, Indeterminate, INFO", "opennms, Cleared, INFO", "opennms, Normal, INFO",
            "opennms, Warning, WARNING", "opennms, Minor, WARNING", "opennms, Major, ERROR",
            "opennms, Critical, CRITICAL",
            "syslog, 5, INFO", "syslog, 6, INFO", "syslog, 7, INFO", "syslog, 4, WARNING", "syslog, 3, ERROR",
            "syslog, 0, CRITICAL", "syslog, 1, CRITICAL", "syslog, 2, CRITICAL",
            "zabbix, Not classified, INFO", "zabbix, Information, INFO", "zabbix, Warning, WARNING",
            "zabbix, Average, ERROR", "zabbix, High, ERROR", "zabbix, Disaster, CRITICAL",
            "icinga, OK, INFO", "icinga, UP, INFO", "icinga, WARNING, WARNING", "icinga, UNKNOWN, WARNING",
            "icinga, CRITICAL, CRITICAL", "icinga, DOWN, CRITICAL",
            "prometheus, info, INFO", "prometheus, none, INFO", "prometheus, warning, WARNING",
            "prometheus, error, ERROR", "prometheus, critical, CRITICAL",
            "windows, Information, INFO", "windows, Verbose, INFO", "windows, Audit Success, INFO",
            "windows, Warning, WARNING", "windows, Error, ERROR", "windows, Audit Failure, ERROR",
            "windows, Critical, CRITICAL"
    })
    void wholeSpecificationTableIsMapped(String system, String nativeSeverity, Severity expected) {
        assertThat(SeverityMapper.map(system, nativeSeverity)).isEqualTo(expected);
    }

    @Test
    void unknownSeverityBecomesWarning() {
        assertThat(SeverityMapper.map("opennms", "Bizarre")).isEqualTo(Severity.WARNING);
    }

    @Test
    void icingaUnreachableIsNotMapped(CapturedOutput output) {
        assertThat(SeverityMapper.map("icinga", "UNREACHABLE")).isEqualTo(Severity.WARNING);
        assertThat(output.getOut()).contains("Severita' nativa non mappata (icinga): 'UNREACHABLE' -> WARNING");
    }

    @Test
    void blankSeverityIsLogged(CapturedOutput output) {
        assertThat(SeverityMapper.map("windows", " ")).isEqualTo(Severity.WARNING);
        assertThat(output.getOut()).contains("Severita' nativa assente (windows) -> WARNING");
    }

    @Test
    void blankPrometheusSeverityIsExpected(CapturedOutput output) {
        assertThat(SeverityMapper.map("prometheus", null)).isEqualTo(Severity.WARNING);
        assertThat(output.getOut()).doesNotContain("Severita' nativa assente (prometheus)");
    }
}
