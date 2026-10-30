package it.infocube.ianus.plugin.core.model;

import java.util.List;

/** Busta della risposta dell'endpoint eventi (sezione 7.3). */
public record EventsResponse(SourceInfo source, WindowInfo window, boolean complete, PageInfo page,
                             List<EventGroup> events) {
}
