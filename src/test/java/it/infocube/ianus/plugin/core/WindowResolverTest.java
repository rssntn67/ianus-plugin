package it.infocube.ianus.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import it.infocube.ianus.plugin.core.error.BadRequestException;
import it.infocube.ianus.plugin.core.window.TimeWindow;
import it.infocube.ianus.plugin.core.window.WindowResolver;
import org.junit.jupiter.api.Test;

class WindowResolverTest {

    private final WindowResolver resolver = new WindowResolver(
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(30)),
            Clock.fixed(Instant.parse("2026-10-14T10:07:30Z"), ZoneOffset.UTC));

    @Test
    void withoutEndUsesLastCompletedWindow() {
        TimeWindow w = resolver.resolve("PT5M", null);
        assertThat(w.start()).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-10-14T10:05:00Z"));
        assertThat(w.toInfo().duration()).isEqualTo("PT5M");
        assertThat(w.toInfo().end()).isEqualTo("2026-10-14T10:05:00Z");
    }

    @Test
    void thirtyMinuteWindowIsAligned() {
        TimeWindow w = resolver.resolve("PT30M", null);
        assertThat(w.start()).isEqualTo(Instant.parse("2026-10-14T09:30:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
    }

    @Test
    void explicitAlignedEnd() {
        TimeWindow w = resolver.resolve("PT30M", "2026-10-14T09:00:00Z");
        assertThat(w.start()).isEqualTo(Instant.parse("2026-10-14T08:30:00Z"));
    }

    @Test
    void windowIncludesStartExcludesEnd() {
        TimeWindow w = resolver.resolve("PT5M", null);
        assertThat(w.contains(Instant.parse("2026-10-14T10:00:00Z"))).isTrue();
        assertThat(w.contains(Instant.parse("2026-10-14T10:05:00Z"))).isFalse();
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> resolver.resolve(null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resolver.resolve("PT10M", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resolver.resolve("five", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resolver.resolve("PT5M", "2026-10-14T10:02:00Z"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resolver.resolve("PT5M", "2026-10-14T10:10:00Z"))
                .isInstanceOf(BadRequestException.class);
    }
}
