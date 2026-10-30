package it.infocube.ianus.plugin.core.source;

import java.time.Duration;
import java.util.List;

public record SourceCapabilities(List<String> metrics, List<String> nodes, Duration historyDepth) {
}
