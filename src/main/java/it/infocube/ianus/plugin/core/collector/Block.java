package it.infocube.ianus.plugin.core.collector;

import java.time.Instant;
import java.util.List;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;

/** Risultato della lettura di [start, end) da una sorgente; complete = false se l'NMS ha risposto solo in parte. */
public record Block(Instant start, Instant end, List<MetricSeries> metrics, List<EventGroup> events,
                    boolean complete) {
}
