package com.moses.monitor.process;

import java.time.Instant;

public record DashboardView(String id,
                            DashboardScope scope,
                            String serviceName,
                            String title,
                            String createdBy,
                            Instant createdAt) {

    static DashboardView of(Dashboard d) {
        return new DashboardView(d.getId(), d.getScope(), d.getServiceName(), d.getTitle(), d.getCreatedBy(),
                d.getCreatedAt());
    }
}
