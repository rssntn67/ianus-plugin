package it.infocube.ianus.plugin.core.util;

import java.util.List;
import java.util.Map;

/** Navigazione tollerante di JSON letto come Map/List (evita dipendenze dalla versione di Jackson). */
public final class Json {

    private Json() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static List<Map<String, Object>> maps(Object o) {
        return list(o).stream().map(Json::map).toList();
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static String str(Object o, String fallback) {
        String s = str(o);
        return s == null || s.isBlank() ? fallback : s;
    }

    /** Numero da Number o da stringa numerica; null se assente o non numerico. */
    public static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Come {@link #num(Object)} ma scarta NaN e infiniti. */
    public static Double finite(Object o) {
        Double d = num(o);
        return d == null || !Double.isFinite(d) ? null : d;
    }
}
