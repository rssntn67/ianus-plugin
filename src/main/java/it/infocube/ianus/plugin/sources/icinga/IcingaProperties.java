package it.infocube.ianus.plugin.sources.icinga;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della sorgente Icinga 2 (prefisso ianus.sources.icinga).
 * L'API REST di Icinga espone solo l'ultimo risultato di ogni controllo: il plugin interroga
 * periodicamente host e servizi e conserva i campioni in memoria per {@code retention}.
 *
 * @param url          es. https://icinga:5665
 * @param pollInterval intervallo di interrogazione (deve essere <= dell'intervallo dei controlli)
 * @param retention    profondita' storica servibile (buffer in memoria)
 */
@ConfigurationProperties("ianus.sources.icinga")
public record IcingaProperties(
        boolean enabled,
        String id,
        String url,
        String username,
        String password,
        Duration connectTimeout,
        Duration readTimeout,
        boolean insecure,
        Duration pollInterval,
        Duration retention) {

    public IcingaProperties {
        id = id == null ? "icinga" : id;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        pollInterval = pollInterval == null ? Duration.ofSeconds(30) : pollInterval;
        retention = retention == null ? Duration.ofHours(24) : retention;
    }
}
