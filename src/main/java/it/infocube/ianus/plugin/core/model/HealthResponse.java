package it.infocube.ianus.plugin.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** status: UP se tutti gli NMS sono raggiungibili, altrimenti DEGRADED. */
public record HealthResponse(String status, @JsonProperty("plugin_version") String pluginVersion,
                             List<SourceHealth> sources) {
}
