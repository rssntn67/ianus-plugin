package it.infocube.ianus.plugin.core.aggregation;

import java.time.Instant;

public record Sample(Instant ts, double value) {
}
