package com.moses.monitor.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsCacheTest {

    private static final Map<String, List<MetricPoint>> DATA = Map.of("a", List.of());

    @Test
    void ttlGrowsWithRangeWithinBounds() {
        MetricsCache cache = new MetricsCache(300);
        assertEquals(Duration.ofSeconds(15), cache.ttlFor(15));
        assertEquals(Duration.ofSeconds(60), cache.ttlFor(60));
        assertEquals(Duration.ofSeconds(300), cache.ttlFor(360));
        assertEquals(Duration.ofSeconds(300), cache.ttlFor(10080));
    }

    @Test
    void secondRequestIsServedFromMemory() {
        MetricsCache cache = new MetricsCache(300);
        AtomicInteger loads = new AtomicInteger();
        cache.get("p", "prod", 60, () -> { loads.incrementAndGet(); return DATA; });
        cache.get("p", "prod", 60, () -> { loads.incrementAndGet(); return DATA; });
        assertEquals(1, loads.get());
    }

    @Test
    void scopesAndRangesAreSeparate() {
        MetricsCache cache = new MetricsCache(300);
        AtomicInteger loads = new AtomicInteger();
        cache.get("p", "prod", 60, () -> { loads.incrementAndGet(); return DATA; });
        cache.get("p", "staging", 60, () -> { loads.incrementAndGet(); return DATA; });
        cache.get("q", "prod", 60, () -> { loads.incrementAndGet(); return DATA; });
        cache.get("p", "prod", 360, () -> { loads.incrementAndGet(); return DATA; });
        assertEquals(4, loads.get());
    }

    @Test
    void concurrentMissesShareOneLoad() throws Exception {
        MetricsCache cache = new MetricsCache(300);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, List<MetricPoint>>>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> cache.get("p", "prod", 60, () -> {
                    loads.incrementAndGet();
                    try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return DATA;
                })));
            }
            Thread.sleep(200);
            release.countDown();
            for (var r : results) assertEquals(DATA, r.get());
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedLoadIsNotCached() {
        MetricsCache cache = new MetricsCache(300);
        assertThrows(IllegalStateException.class,
                () -> cache.get("p", "prod", 60, () -> { throw new IllegalStateException("mongo down"); }));
        AtomicInteger loads = new AtomicInteger();
        cache.get("p", "prod", 60, () -> { loads.incrementAndGet(); return DATA; });
        assertEquals(1, loads.get());
    }
}
