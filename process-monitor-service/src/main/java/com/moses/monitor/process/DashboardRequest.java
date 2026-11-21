package com.moses.monitor.process;

import jakarta.validation.constraints.NotNull;

public class DashboardRequest {

    @NotNull
    private DashboardScope scope;

    /** Required when scope is SERVICE, ignored for ENVIRONMENT. */
    private String serviceName;

    /** Optional; a sensible default is used when left out. */
    private String title;

    /** Defaults to SHARED. PRIVATE needs a signed-in user (not an API key). */
    private DashboardVisibility visibility;

    public DashboardVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(DashboardVisibility visibility) {
        this.visibility = visibility;
    }

    /** Who's asking: "agent" for a monitored service's own integration agent, "user" for the UI. */
    private String createdBy;

    public DashboardScope getScope() {
        return scope;
    }

    public void setScope(DashboardScope scope) {
        this.scope = scope;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
