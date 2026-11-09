package it.infocube.ianus.plugin.core.severity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import it.infocube.ianus.plugin.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mappatura dalle severita' native alla scala IANUS (sezione 6.1).
 * Una severita' assente dalla tabella, o vuota, diventa WARNING e viene segnalata nel log;
 * per Prometheus l'etichetta severity assente e' prevista dalla tabella e non si segnala.
 */
public final class SeverityMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SeverityMapper.class);
    private static final Map<String, Map<String, Severity>> TABLE = new HashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    static {
        put("opennms", Severity.INFO, "indeterminate", "cleared", "normal");
        put("opennms", Severity.WARNING, "warning", "minor");
        put("opennms", Severity.ERROR, "major");
        put("opennms", Severity.CRITICAL, "critical");

        put("syslog", Severity.INFO, "5", "6", "7", "notice", "informational", "info", "debug");
        put("syslog", Severity.WARNING, "4", "warning");
        put("syslog", Severity.ERROR, "3", "error", "err");
        put("syslog", Severity.CRITICAL, "0", "1", "2", "emergency", "emerg", "alert", "critical", "crit");

        put("zabbix", Severity.INFO, "not classified", "information");
        put("zabbix", Severity.WARNING, "warning");
        put("zabbix", Severity.ERROR, "average", "high");
        put("zabbix", Severity.CRITICAL, "disaster");

        put("icinga", Severity.INFO, "ok", "up");
        put("icinga", Severity.WARNING, "warning", "unknown");
        put("icinga", Severity.CRITICAL, "critical", "down");

        put("prometheus", Severity.INFO, "info", "none");
        put("prometheus", Severity.WARNING, "warning");
        put("prometheus", Severity.ERROR, "error");
        put("prometheus", Severity.CRITICAL, "critical");

        put("windows", Severity.INFO, "information", "verbose", "audit success");
        put("windows", Severity.WARNING, "warning");
        put("windows", Severity.ERROR, "error", "audit failure");
        put("windows", Severity.CRITICAL, "critical");
    }

    private SeverityMapper() {
    }

    public static Severity map(String system, String nativeSeverity) {
        if (nativeSeverity == null || nativeSeverity.isBlank()) {
            // Prometheus: etichetta severity assente -> WARNING per specifica
            if (!"prometheus".equals(system) && WARNED.add(system + "/")) {
                LOG.warn("Severita' nativa assente ({}) -> WARNING", system);
            }
            return Severity.WARNING;
        }
        String key = nativeSeverity.trim().toLowerCase(Locale.ROOT);
        Severity s = TABLE.getOrDefault(system, Map.of()).get(key);
        if (s == null) {
            if (WARNED.add(system + "/" + key)) {
                LOG.warn("Severita' nativa non mappata ({}): '{}' -> WARNING", system, nativeSeverity);
            }
            return Severity.WARNING;
        }
        return s;
    }

    private static void put(String system, Severity severity, String... natives) {
        Map<String, Severity> m = TABLE.computeIfAbsent(system, k -> new HashMap<>());
        for (String n : natives) {
            m.put(n, severity);
        }
    }
}
