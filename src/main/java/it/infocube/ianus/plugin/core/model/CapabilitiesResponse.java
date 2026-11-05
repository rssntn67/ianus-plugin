package it.infocube.ianus.plugin.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CapabilitiesResponse(
        @JsonProperty(required = true) SourceInfo source,
        @JsonProperty(required = true) List<String> windows,
        @JsonProperty(required = true) List<String> metrics,
        @JsonProperty(required = true) List<String> nodes,
        @JsonProperty(value = "history_depth", required = true) String historyDepth) {
}
