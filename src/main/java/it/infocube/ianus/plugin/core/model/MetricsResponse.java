package it.infocube.ianus.plugin.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Busta della risposta dell'endpoint metriche (sezione 7.3). */
public record MetricsResponse(
        @JsonProperty(required = true) SourceInfo source,
        @JsonProperty(required = true) WindowInfo window,
        @JsonProperty(required = true) boolean complete,
        @JsonProperty(required = true) PageInfo page,
        @JsonProperty(required = true) List<MetricSeries> metrics) {
}
