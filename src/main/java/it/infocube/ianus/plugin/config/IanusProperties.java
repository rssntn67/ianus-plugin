package it.infocube.ianus.plugin.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Impostazioni generali del plugin (prefisso {@code ianus}).
 *
 * @param apiKey          chiave che IANUS deve inviare nell'header X-API-Key (obbligatoria)
 * @param pluginVersion   versione riportata nella busta delle risposte
 * @param windows         finestre supportate: devono comprendere PT5M e PT30M (sezione 3.1)
 * @param defaultPageSize dimensione di pagina se il parametro size non e' indicato
 * @param maxPageSize     dimensione massima di pagina
 * @param retention       storia conservata in memoria dai Collector
 */
@ConfigurationProperties("ianus")
public record IanusProperties(
        String apiKey,
        String pluginVersion,
        List<Duration> windows,
        Integer defaultPageSize,
        Integer maxPageSize,
        Duration retention) {

    public IanusProperties {
        pluginVersion = pluginVersion == null ? "2.0.0" : pluginVersion;
        windows = windows == null || windows.isEmpty()
                ? List.of(Duration.ofMinutes(5), Duration.ofMinutes(30))
                : List.copyOf(windows);
        for (Duration required : List.of(Duration.ofMinutes(5), Duration.ofMinutes(30))) {
            if (!windows.contains(required)) {
                throw new IllegalArgumentException("ianus.windows deve comprendere " + required + ": " + windows);
            }
        }
        defaultPageSize = defaultPageSize == null ? 500 : defaultPageSize;
        maxPageSize = maxPageSize == null ? 5000 : maxPageSize;
        retention = retention == null ? Duration.ofHours(3) : retention;
    }
}
