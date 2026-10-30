package it.infocube.ianus.plugin.core.model;

import java.util.List;

/** Busta della risposta dell'endpoint metriche (sezione 7.3). */
public record MetricsResponse(SourceInfo source, WindowInfo window, boolean complete, PageInfo page,
                              List<MetricSeries> metrics) {
}
