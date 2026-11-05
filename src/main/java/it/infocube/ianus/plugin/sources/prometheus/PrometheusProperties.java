package it.infocube.ianus.plugin.sources.prometheus;

import java.time.Duration;
import java.util.List;

import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della sorgente Prometheus (prefisso ianus.sources.prometheus).
 *
 * @param nodeLabel      etichetta che identifica il nodo (default instance)
 * @param rateWindow     finestra di rate() per i contatori
 * @param subqueryStep   passo della subquery con cui si calcolano media/min/max dei tassi
 * @param alertStep      passo con cui si campiona la serie ALERTS per gli eventi
 * @param seriesLookback periodo prima della fine della finestra in cui cercare le serie note: una serie
 *                       con campioni in questo periodo ma non nella finestra esce con valori null
 * @param metrics        metriche esposte a IANUS
 */
@ConfigurationProperties("ianus.sources.prometheus")
public record PrometheusProperties(
        boolean enabled,
        String id,
        String url,
        String username,
        String password,
        String bearerToken,
        Duration connectTimeout,
        Duration readTimeout,
        boolean insecure,
        Duration historyDepth,
        String nodeLabel,
        Duration rateWindow,
        Duration subqueryStep,
        Duration alertStep,
        Duration seriesLookback,
        List<PromMetric> metrics) {

    /**
     * @param name           nome IANUS della metrica (es. cpu_usage)
     * @param selector       selettore PromQL (es. node_network_receive_bytes_total{device!="lo"})
     * @param kind           gauge o counter
     * @param unit           unita' IANUS (per i counter: unita' al secondo, es. bytes_per_second)
     * @param instanceLabels etichette che compongono il campo instance, unite da "/"
     */
    public record PromMetric(String name, String selector, MetricKind kind, String unit, List<String> instanceLabels) {

        public PromMetric {
            selector = selector == null || selector.isBlank() ? name : selector;
            kind = kind == null ? MetricKind.GAUGE : kind;
            unit = unit == null || unit.isBlank() ? "none" : unit;
            instanceLabels = instanceLabels == null ? List.of() : List.copyOf(instanceLabels);
        }
    }

    public PrometheusProperties {
        id = id == null ? "prometheus" : id;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        historyDepth = historyDepth == null ? Duration.ofDays(15) : historyDepth;
        nodeLabel = nodeLabel == null || nodeLabel.isBlank() ? "instance" : nodeLabel;
        rateWindow = rateWindow == null ? Duration.ofMinutes(1) : rateWindow;
        subqueryStep = subqueryStep == null ? Duration.ofSeconds(30) : subqueryStep;
        alertStep = alertStep == null ? Duration.ofSeconds(15) : alertStep;
        seriesLookback = seriesLookback == null ? Duration.ofHours(1) : seriesLookback;
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
