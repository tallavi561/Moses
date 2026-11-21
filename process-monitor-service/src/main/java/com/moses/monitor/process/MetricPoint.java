package com.moses.monitor.process;

import java.time.Instant;

/**
 * One point of a service's chart. Heartbeat samples carry cpu/memory; error-rate points
 * ({@link MetricsController#errorRates}) carry errorsPerMinute. Fields a point doesn't have are null.
 */
public record MetricPoint(Instant timestamp, Double cpuPercent, Double memoryMb, Double errorsPerMinute) {

    static MetricPoint of(MetricSample s) {
        return new MetricPoint(s.getTimestamp(), s.getCpuPercent(), s.getMemoryMb(), null);
    }

    static MetricPoint errors(Instant bucketStart, double perMinute) {
        return new MetricPoint(bucketStart, null, null, perMinute);
    }
}
