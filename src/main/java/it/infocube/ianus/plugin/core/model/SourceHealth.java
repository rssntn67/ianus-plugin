package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceHealth(
        @JsonProperty(required = true) String system,
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) boolean reachable,
        String message) {
}
