package it.infocube.ianus.plugin.core.util;

import java.util.Locale;

/** Normalizzazione dei nomi di metrica: minuscolo con underscore. */
public final class Names {

    private Names() {
    }

    /** ifHCInOctets -> if_hc_in_octets, system.cpu.util -> system_cpu_util. */
    public static String snake(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String s = name.trim()
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return s.toLowerCase(Locale.ROOT);
    }

    public static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
