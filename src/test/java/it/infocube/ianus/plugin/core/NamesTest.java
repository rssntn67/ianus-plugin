package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import it.infocube.ianus.plugin.core.util.Names;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void snakeCase() {
        assertThat(Names.snake("ifHCInOctets")).isEqualTo("if_hc_in_octets");
        assertThat(Names.snake("system.cpu.util")).isEqualTo("system_cpu_util");
        assertThat(Names.snake("CPU Usage %")).isEqualTo("cpu_usage");
        assertThat(Names.snake("rta")).isEqualTo("rta");
    }
}
