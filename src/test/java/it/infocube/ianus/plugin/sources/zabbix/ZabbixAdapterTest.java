package it.infocube.ianus.plugin.sources.zabbix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import it.infocube.ianus.plugin.core.model.RawEvent;
import it.infocube.ianus.plugin.core.model.Severity;
import org.junit.jupiter.api.Test;

class ZabbixAdapterTest {

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
