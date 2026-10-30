package it.infocube.ianus.plugin.core.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Cache in memoria delle risposte complete per le finestre recenti (sezione 8). */
public class ResultCache {

    private record Entry(Object value, Instant expires) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    public ResultCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader, Predicate<T> cacheable) {
        Instant now = clock.instant();
        Entry e = entries.get(key);
        if (e != null && e.expires().isAfter(now)) {
            return (T) e.value();
        }
        T value = loader.get();
        if (ttl.isPositive() && cacheable.test(value)) {
            if (entries.size() >= maxEntries) {
                entries.entrySet().removeIf(x -> !x.getValue().expires().isAfter(now));
                if (entries.size() >= maxEntries) {
                    entries.clear();
                }
            }
            entries.put(key, new Entry(value, now.plus(ttl)));
        }
        return value;
    }
}
