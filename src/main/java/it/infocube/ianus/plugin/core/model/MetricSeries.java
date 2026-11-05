package it.infocube.ianus.plugin.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import it.infocube.ianus.plugin.core.aggregation.Stats;

/**
 * Serie di performance sintetica sulla finestra (sezione 4).
 * value = media, min/max = estremi; null con samples = 0 se non ci sono campioni.
 */
public record MetricSeries(
        @JsonProperty(required = true) String node,
        @JsonProperty(required = true) String metric,
        @JsonProperty(required = true) String instance,
        @JsonProperty(required = true) String unit,
        @JsonProperty(required = true) @Schema(types = {"number", "null"}) Double value,
        @JsonProperty(required = true) @Schema(types = {"number", "null"}) Double min,
        @JsonProperty(required = true) @Schema(types = {"number", "null"}) Double max,
        @JsonProperty(required = true) long samples,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> labels) {

    public MetricSeries {
        node = node == null ? "" : node;
        metric = metric == null ? "" : metric;
        instance = instance == null ? "" : instance;
        unit = unit == null || unit.isBlank() ? "none" : unit;
        labels = labels == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(labels));
    }

    public static MetricSeries of(String node, String metric, String instance, String unit,
                                  Stats stats, Map<String, String> labels) {
        return new MetricSeries(node, metric, instance, unit,
                stats.avg(), stats.min(), stats.max(), stats.samples(), labels);
    }
}
