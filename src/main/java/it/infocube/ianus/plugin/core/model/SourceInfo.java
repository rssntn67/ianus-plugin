package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceInfo(String system, String id, @JsonProperty("plugin_version") String pluginVersion) {
}
