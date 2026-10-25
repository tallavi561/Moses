package com.moses.monitor.ingestion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory accumulator for index updates, so that concurrent
 * ingestion workers never hit Mongo on a per-log basis. A scheduled flush
 * (see IndexFlushScheduler) periodically swaps the buffer and bulk-writes it.
 */
@Component
public class IngestionBuffer {

    public static final class BucketEntry {
        final Instant bucketStart;
        final ConcurrentHashMap<String, Set<String>> wordToLogIds = new ConcurrentHashMap<>();

        BucketEntry(Instant bucketStart) {
            this.bucketStart = bucketStart;
        }

        public Instant getBucketStart() {
            return bucketStart;
        }

        public ConcurrentHashMap<String, Set<String>> getWordToLogIds() {
            return wordToLogIds;
        }
    }

    private final AtomicReference<ConcurrentHashMap<String, BucketEntry>> current =
            new AtomicReference<>(new ConcurrentHashMap<>());

    public void record(String bucketKey, Instant bucketStart, List<String> words, String logId) {
        ConcurrentHashMap<String, BucketEntry> map = current.get();
        BucketEntry entry = map.computeIfAbsent(bucketKey, k -> new BucketEntry(bucketStart));
        for (String word : words) {
            entry.wordToLogIds
                    .computeIfAbsent(word, w -> ConcurrentHashMap.newKeySet())
                    .add(logId);
        }
    }

    /** Atomically swaps in a fresh empty buffer and returns the drained snapshot. */
    public ConcurrentHashMap<String, BucketEntry> drainAndSwap() {
        return current.getAndSet(new ConcurrentHashMap<>());
    }
}
