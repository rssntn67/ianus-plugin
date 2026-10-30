package it.infocube.ianus.plugin.core.source;

import java.util.List;

/** Risultato di una sorgente; complete = false se l'NMS ha risposto solo in parte. */
public record SourceResult<T>(List<T> items, boolean complete) {

    public SourceResult {
        items = List.copyOf(items);
    }
}
