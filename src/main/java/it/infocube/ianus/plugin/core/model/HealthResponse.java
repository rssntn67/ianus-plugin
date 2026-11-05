package it.infocube.ianus.plugin.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** status: UP se tutti gli NMS sono raggiungibili, altrimenti DEGRADED. */
public record HealthResponse(
        @JsonProperty(required = true) String status,
        @JsonProperty(value = "plugin_version", required = true) String pluginVersion,
        @JsonProperty(required = true) List<SourceHealth> sources) {
}
