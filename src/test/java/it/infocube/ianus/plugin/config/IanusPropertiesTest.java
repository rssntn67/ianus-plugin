package it.infocube.ianus.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class IanusPropertiesTest {

    @Test
    void windowsMustIncludeFiveAndThirtyMinutes() {
        assertThatThrownBy(() -> new IanusProperties("k", null, List.of(Duration.ofMinutes(5)), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT30M");
    }

    @Test
    void furtherWindowsAreAllowed() {
        List<Duration> windows = List.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(1));

        assertThat(new IanusProperties("k", null, windows, null, null, null).windows()).isEqualTo(windows);
    }
}
