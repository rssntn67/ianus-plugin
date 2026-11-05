package it.infocube.ianus.plugin.sources.zabbix;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della sorgente Zabbix (prefisso ianus.sources.zabbix).
 *
 * @param url          endpoint JSON-RPC, es. https://zabbix/api_jsonrpc.php
 * @param token        API token di un utente in sola lettura
 * @param authMode     header (Authorization: Bearer, Zabbix 6.4+) oppure param (campo auth, versioni precedenti)
 * @param hostGroupIds gruppi di host da esporre; vuoto = tutti gli host monitorati
 * @param maxEvents    eventi massimi letti per finestra
 * @param counterKeys  chiavi (senza parametri) degli item contatore: se non hanno gia' un preprocessing
 *                     di variazione, il plugin ne calcola il tasso al secondo
 * @param counterLookback periodo prima del blocco in cui cercare il campione precedente dei contatori:
 *                     deve superare l'intervallo di aggiornamento degli item
 */
@ConfigurationProperties("ianus.sources.zabbix")
public record ZabbixProperties(
        boolean enabled,
        String id,
        String url,
        String token,
        String authMode,
        Duration connectTimeout,
        Duration readTimeout,
        boolean insecure,
        List<String> hostGroupIds,
        Duration discoveryRefresh,
        Integer batchSize,
        Integer maxEvents,
        List<String> counterKeys,
        Duration counterLookback) {

    public ZabbixProperties {
        id = id == null ? "zabbix" : id;
        authMode = authMode == null || authMode.isBlank() ? "header" : authMode;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        hostGroupIds = hostGroupIds == null ? List.of() : List.copyOf(hostGroupIds);
        discoveryRefresh = discoveryRefresh == null ? Duration.ofHours(1) : discoveryRefresh;
        batchSize = batchSize == null ? 500 : batchSize;
        maxEvents = maxEvents == null ? 10_000 : maxEvents;
        counterKeys = counterKeys == null ? List.of() : List.copyOf(counterKeys);
        counterLookback = counterLookback == null ? Duration.ofMinutes(15) : counterLookback;
    }
}
