package it.infocube.ianus.plugin.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Raccolta periodica di ogni sorgente (ianus.sources.&lt;sorgente&gt;.collect-*). */
@ConfigurationProperties("ianus")
public record CollectorProperties(Map<String, CollectSettings> sources) {

    /**
     * @param collectInterval ampiezza dei blocchi e frequenza di raccolta
     * @param collectDelay    attesa dopo la fine del blocco, perche' l'NMS abbia i dati
     */
    public record CollectSettings(Duration collectInterval, Duration collectDelay) {

        public CollectSettings {
            collectInterval = collectInterval == null ? Duration.ofMinutes(5) : collectInterval;
            collectDelay = collectDelay == null ? Duration.ofMinutes(1) : collectDelay;
        }
    }

    public CollectorProperties {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public CollectSettings settings(String system) {
        CollectSettings settings = sources.get(system);
        return settings == null ? new CollectSettings(null, null) : settings;
    }
}
