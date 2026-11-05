package it.infocube.ianus.plugin.sources.opennms;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della sorgente OpenNMS (prefisso ianus.sources.opennms).
 *
 * @param url               es. http://opennms:8980/opennms
 * @param nodes             etichette dei nodi da esporre; vuoto = tutti i nodi
 * @param sampleStep        passo dei campioni richiesti alla Measurements API (intervallo di raccolta)
 * @param discoveryRefresh  ogni quanto rileggere nodi, risorse e attributi
 * @param batchSize         attributi per singola richiesta alla Measurements API
 * @param units             unita' di misura per attributo (OpenNMS non le espone)
 * @param maxEventPages     pagine massime di eventi (1000 l'una) per finestra
 */
@ConfigurationProperties("ianus.sources.opennms")
public record OpenNmsProperties(
        boolean enabled,
        String id,
        String url,
        String username,
        String password,
        Duration connectTimeout,
        Duration readTimeout,
        boolean insecure,
        List<String> nodes,
        Duration sampleStep,
        Duration discoveryRefresh,
        Integer batchSize,
        List<UnitMapping> units,
        Integer maxEventPages) {

    public record UnitMapping(String attribute, String unit) {
    }

    public OpenNmsProperties {
        id = id == null ? "opennms" : id;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        sampleStep = sampleStep == null ? Duration.ofMinutes(5) : sampleStep;
        discoveryRefresh = discoveryRefresh == null ? Duration.ofHours(1) : discoveryRefresh;
        batchSize = batchSize == null ? 50 : batchSize;
        units = units == null ? List.of() : List.copyOf(units);
        maxEventPages = maxEventPages == null ? 20 : maxEventPages;
    }
}
