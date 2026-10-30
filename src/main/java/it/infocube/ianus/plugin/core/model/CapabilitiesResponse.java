package it.infocube.ianus.plugin.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CapabilitiesResponse(
        SourceInfo source,
        List<String> windows,
        List<String> metrics,
        List<String> nodes,
        @JsonProperty("history_depth") String historyDepth) {
}
