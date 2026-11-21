package com.moses.monitor.process;

import java.time.Instant;
import java.util.List;

/** A dashboard as the UI sees it; chart settings are always filled in (defaults applied). */
public record DashboardView(String id,
                            DashboardScope scope,
                            String serviceName,
                            String title,
                            List<String> services,
                            String metric,
                            int minutesBack,
                            DashboardVisibility visibility,
                            /** The asking user created it (only they can make it private or shared). */
                            boolean mine,
                            String createdBy,
                            Instant createdAt) {

    static final String DEFAULT_METRIC = "cpu";
    static final int DEFAULT_MINUTES_BACK = 60;

    /** Empty list = every service in the environment. */
    static DashboardView of(Dashboard d, String userId) {
        List<String> services = d.getServices() != null ? d.getServices()
                : d.getScope() == DashboardScope.SERVICE && d.getServiceName() != null ? List.of(d.getServiceName())
                : List.of();
        return new DashboardView(d.getId(), d.getScope(), d.getServiceName(), d.getTitle(), services,
                d.getMetric() != null ? d.getMetric() : DEFAULT_METRIC,
                d.getMinutesBack() != null ? d.getMinutesBack() : DEFAULT_MINUTES_BACK,
                d.getVisibility(), userId != null && userId.equals(d.getOwnerUserId()),
                d.getCreatedBy(), d.getCreatedAt());
    }
}
