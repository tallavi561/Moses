package com.moses.monitor.process;

import java.util.List;

public record ServiceMetrics(String serviceName, List<MetricPoint> samples) {
}
