package com.moses.monitor.process;

import java.time.Instant;

public record MetricPoint(Instant timestamp, Double cpuPercent, Double memoryMb) {

    static MetricPoint of(MetricSample s) {
        return new MetricPoint(s.getTimestamp(), s.getCpuPercent(), s.getMemoryMb());
    }
}
