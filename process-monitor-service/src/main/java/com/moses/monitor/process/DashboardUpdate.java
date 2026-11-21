package com.moses.monitor.process;

import java.util.List;
import java.util.Set;

/**
 * A partial change to a dashboard: fields left null stay as they are. Sent by the UI, and by
 * ai-service after turning a free-text instruction ("show only memory for checkout, last 6h")
 * into these fields.
 */
public record DashboardUpdate(String title, List<String> services, String metric, Integer minutesBack,
                              DashboardVisibility visibility) {

    static final Set<String> METRICS = Set.of("cpu", "memory", "errors");
    /** The time ranges the chart offers. */
    static final List<Integer> RANGES = List.of(15, 60, 360, 1440, 10080);
    static final int MAX_TITLE = 120;
    static final int MAX_SERVICES = 50;
}
