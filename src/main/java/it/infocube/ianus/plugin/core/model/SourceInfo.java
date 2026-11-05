package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceInfo(
        @JsonProperty(required = true) String system,
        @JsonProperty(required = true) String id,
        @JsonProperty(value = "plugin_version", required = true) String pluginVersion) {
}
