package it.infocube.ianus.plugin.sources.icinga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.sources.icinga.PerfdataParser.PerfValue;
import org.junit.jupiter.api.Test;

class PerfdataParserTest {

    @Test
    void parsesClassicPerfdata() {
        List<PerfValue> v = PerfdataParser.parse("rta=0.123ms;100;500;0 pl=0%;5;10;0");
        assertThat(v).hasSize(2);
        assertThat(v.get(0).label()).isEqualTo("rta");
        assertThat(v.get(0).value()).isCloseTo(0.000123, within(1e-12));
        assertThat(v.get(0).unit()).isEqualTo("seconds");
        assertThat(v.get(1).unit()).isEqualTo("percent");
    }

    @Test
    void quotedLabelsUnitsCountersAndUnknown() {
        List<PerfValue> v = PerfdataParser.parse("'C:\\ used'=2GB;;;0;100 'it''s'=3 in_octets=12345c load=U");
        assertThat(v).extracting(PerfValue::label).containsExactly("C:\\ used", "it's", "in_octets");
        assertThat(v.get(0).value()).isEqualTo(2.0 * 1024 * 1024 * 1024);
        assertThat(v.get(0).unit()).isEqualTo("bytes");
        assertThat(v.get(1).unit()).isEqualTo("none");
        assertThat(v.get(2).kind()).isEqualTo(MetricKind.COUNTER);
    }

    @Test
    void parsesPerfdataObjects() {
        List<PerfValue> v = PerfdataParser.parseEntry(Map.of("label", "load1", "value", 0.5, "unit", "", "counter", false));
        assertThat(v).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo("load1");
            assertThat(p.unit()).isEqualTo("none");
        });
    }

    @Test
    void stateNames() {
        assertThat(IcingaAdapter.stateName(false, 2)).isEqualTo("CRITICAL");
        assertThat(IcingaAdapter.stateName(true, 1)).isEqualTo("DOWN");
    }
}
