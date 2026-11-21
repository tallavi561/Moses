package com.moses.monitor.process;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * In-memory cache of the metrics chart's data: every service's history for one environment and
 * time range, shared by everyone looking at that environment (and by the chat agent). One
 * request per range and lifetime reaches Mongo; the rest - other users, other dashboards, a
 * different service selection - are answered from memory.
 *
 * How long an entry lives grows with the range it covers: a 15-minute chart must stay close to
 * live, a 7-day one barely changes in a few minutes. Concurrent misses for the same key share
 * one load instead of each querying Mongo.
 */
@Component
public class MetricsCache {

    static final Duration MIN_TTL = Duration.ofSeconds(15);
    private static final int MAX_ENTRIES = 2_000;

    private record Entry(CompletableFuture<Map<String, List<MetricPoint>>> value, long expiresAtMs) { }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration maxTtl;

    public MetricsCache(@Value("${moses.metrics.cache-max-seconds:300}") int maxTtlSeconds) {
        this.maxTtl = Duration.ofSeconds(Math.max(maxTtlSeconds, MIN_TTL.toSeconds()));
    }

    /** One second of cache per minute of range, between 15 seconds and the configured maximum. */
    Duration ttlFor(int minutesBack) {
        Duration ttl = Duration.ofSeconds(minutesBack);
        if (ttl.compareTo(MIN_TTL) < 0) return MIN_TTL;
        return ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl;
    }

    Map<String, List<MetricPoint>> get(String projectId, String environment, int minutesBack,
                                       Supplier<Map<String, List<MetricPoint>>> loader) {
        return get("samples", projectId, environment, minutesBack, loader);
    }

    /** {@code kind} keeps different series of the same environment and range apart (CPU/memory samples, error rates). */
    Map<String, List<MetricPoint>> get(String kind, String projectId, String environment, int minutesBack,
                                       Supplier<Map<String, List<MetricPoint>>> loader) {
        String key = kind + "|" + projectId + "|" + environment + "|" + minutesBack;
        long now = System.currentTimeMillis();
        CompletableFuture<Map<String, List<MetricPoint>>> mine = new CompletableFuture<>();
        Entry entry = entries.compute(key, (k, existing) ->
                existing != null && existing.expiresAtMs() > now && !existing.value().isCompletedExceptionally()
                        ? existing
                        : new Entry(mine, now + ttlFor(minutesBack).toMillis()));
        if (entry.value() == mine) {
            if (entries.size() > MAX_ENTRIES) {
                entries.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= now);
            }
            try {
                mine.complete(loader.get());
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                entries.remove(key, entry);
                throw e;
            }
        }
        try {
            return entry.value().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }
}
