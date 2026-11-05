package it.infocube.ianus.plugin.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Impostazioni generali del plugin (prefisso {@code ianus}).
 *
 * @param apiKey          chiave che IANUS deve inviare nell'header X-API-Key (obbligatoria)
 * @param pluginVersion   versione riportata nella busta delle risposte
 * @param windows         finestre supportate (almeno PT5M e PT30M)
 * @param defaultPageSize dimensione di pagina se il parametro size non e' indicato
 * @param maxPageSize     dimensione massima di pagina
 * @param cacheTtl        durata della cache delle risposte complete (PT0S per disattivarla)
 * @param cacheMaxEntries numero massimo di risposte in cache
 * @param retention       storia conservata in memoria dai Collector
 */
@ConfigurationProperties("ianus")
public record IanusProperties(
        String apiKey,
        String pluginVersion,
        List<Duration> windows,
        Integer defaultPageSize,
        Integer maxPageSize,
        Duration cacheTtl,
        Integer cacheMaxEntries,
        Duration retention) {

    public IanusProperties {
        pluginVersion = pluginVersion == null ? "2.0.0" : pluginVersion;
        windows = windows == null || windows.isEmpty()
                ? List.of(Duration.ofMinutes(5), Duration.ofMinutes(30))
                : List.copyOf(windows);
        defaultPageSize = defaultPageSize == null ? 500 : defaultPageSize;
        maxPageSize = maxPageSize == null ? 5000 : maxPageSize;
        cacheTtl = cacheTtl == null ? Duration.ofMinutes(10) : cacheTtl;
        cacheMaxEntries = cacheMaxEntries == null ? 200 : cacheMaxEntries;
        retention = retention == null ? Duration.ofHours(3) : retention;
    }
}
