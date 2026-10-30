package it.infocube.ianus.plugin.sources.icinga;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.infocube.ianus.plugin.core.aggregation.MetricKind;
import it.infocube.ianus.plugin.core.util.Json;

/**
 * Parser dei performance data dei plugin di controllo:
 * {@code 'etichetta'=valore[UOM];warn;crit;min;max}, separati da spazi.
 * I valori sono convertiti in unita' IANUS (ms -> seconds, KB -> bytes, c -> contatore).
 */
public final class PerfdataParser {

    public record PerfValue(String label, double value, String unit, MetricKind kind) {
    }

    private static final Pattern VALUE = Pattern.compile("^([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)(.*)$");

    private PerfdataParser() {
    }

    /** Voce di performance_data: stringa (formato classico) o oggetto PerfdataValue. */
    public static List<PerfValue> parseEntry(Object entry) {
        if (entry instanceof Map<?, ?>) {
            Map<String, Object> m = Json.map(entry);
            String label = Json.str(m.get("label"));
            Double value = Json.finite(m.get("value"));
            if (label == null || value == null) {
                return List.of();
            }
            boolean counter = Boolean.TRUE.equals(m.get("counter"));
            String unit = Json.str(m.get("unit"), "");
            if (counter) {
                return List.of(new PerfValue(label, value, "per_second", MetricKind.COUNTER));
            }
            return List.of(new PerfValue(label, value, objectUnit(unit), MetricKind.GAUGE));
        }
        return parse(Json.str(entry));
    }

    public static List<PerfValue> parse(String text) {
        List<PerfValue> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            String label;
            if (text.charAt(i) == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char c = text.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < n && text.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(c);
                    i++;
                }
                label = sb.toString();
                if (i >= n || text.charAt(i) != '=') {
                    i = nextWhitespace(text, i);
                    continue;
                }
                i++;
            } else {
                int end = nextWhitespace(text, i);
                int eq = text.indexOf('=', i);
                if (eq < 0 || eq > end) {
                    i = end;
                    continue;
                }
                label = text.substring(i, eq);
                i = eq + 1;
            }
            int end = nextWhitespace(text, i);
            String spec = text.substring(i, end);
            i = end;
            String valueAndUom = spec.split(";", -1)[0].replace(',', '.');
            Matcher m = VALUE.matcher(valueAndUom);
            if (!label.isEmpty() && m.matches()) {
                out.add(convert(label, Double.parseDouble(m.group(1)), m.group(2).trim()));
            }
        }
        return out;
    }

    static PerfValue convert(String label, double v, String uom) {
        return switch (uom) {
            case "" -> new PerfValue(label, v, "none", MetricKind.GAUGE);
            case "%" -> new PerfValue(label, v, "percent", MetricKind.GAUGE);
            case "s" -> new PerfValue(label, v, "seconds", MetricKind.GAUGE);
            case "ms" -> new PerfValue(label, v / 1_000.0, "seconds", MetricKind.GAUGE);
            case "us" -> new PerfValue(label, v / 1_000_000.0, "seconds", MetricKind.GAUGE);
            case "B" -> new PerfValue(label, v, "bytes", MetricKind.GAUGE);
            case "KB" -> new PerfValue(label, v * 1024, "bytes", MetricKind.GAUGE);
            case "MB" -> new PerfValue(label, v * 1024 * 1024, "bytes", MetricKind.GAUGE);
            case "GB" -> new PerfValue(label, v * 1024 * 1024 * 1024, "bytes", MetricKind.GAUGE);
            case "TB" -> new PerfValue(label, v * 1024 * 1024 * 1024 * 1024, "bytes", MetricKind.GAUGE);
            case "c" -> new PerfValue(label, v, "per_second", MetricKind.COUNTER);
            default -> new PerfValue(label, v, uom.toLowerCase(Locale.ROOT), MetricKind.GAUGE);
        };
    }

    private static String objectUnit(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "" -> "none";
            case "percentage" -> "percent";
            default -> unit.toLowerCase(Locale.ROOT);
        };
    }

    private static int nextWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
