package it.infocube.ianus.plugin.core.source;

import it.infocube.ianus.plugin.core.model.EventGroup;
import it.infocube.ianus.plugin.core.model.MetricSeries;
import it.infocube.ianus.plugin.core.model.SourceHealth;
import it.infocube.ianus.plugin.core.window.TimeWindow;

/**
 * Plugin verso un NMS. Ogni implementazione interroga il proprio sistema sulla finestra
 * richiesta e restituisce dati gia' sintetici nel modello IANUS.
 */
public interface SourceAdapter {

    /** Prefisso della sorgente nell'URL: opennms, prometheus, icinga, zabbix. */
    String system();

    /** Identificativo dell'installazione (es. opennms-napoli-01). */
    String instanceId();

    SourceResult<MetricSeries> metrics(TimeWindow window, QueryFilter filter);

    SourceResult<EventGroup> events(TimeWindow window, QueryFilter filter);

    SourceCapabilities capabilities();

    SourceHealth health();
}
