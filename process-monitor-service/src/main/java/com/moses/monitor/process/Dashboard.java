package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * A saved monitoring view for one environment, or for one service within it (the codebase has no
 * separate "process" concept - a service's heartbeat IS its process state - so "per process" and
 * "per service" are the same scope here). Created either by a project member in the UI, or by a
 * monitored service's own integration agent requesting one for itself via its API key.
 */
@Document(collection = "dashboards")
public class Dashboard {

    @Id
    private String id;

    @Field("project_id")
    private String projectId;

    @Field("environment")
    private String environment;

    @Field("scope")
    private DashboardScope scope;

    /** Null for an ENVIRONMENT-scoped dashboard; required for a SERVICE-scoped one. */
    @Field("service_name")
    private String serviceName;

    @Field("title")
    private String title;

    /**
     * Chart settings, editable from the UI by hand or in free text (via ai-service). Null means
     * the default: the scope's services, CPU, the last hour.
     */
    @Field("services")
    private List<String> services;

    @Field("metric")
    private String metric;

    @Field("minutes_back")
    private Integer minutesBack;

    @Field("created_by")
    private String createdBy;

    /** Null on dashboards made before visibility existed - those are shared. */
    @Field("visibility")
    private DashboardVisibility visibility;

    /** The user who created it; null when a service's integration agent asked for it. */
    @Field("owner_user_id")
    private String ownerUserId;

    @Field("created_at")
    private Instant createdAt;

    public Dashboard() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

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

    public List<String> getServices() {
        return services;
    }

    public void setServices(List<String> services) {
        this.services = services;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Integer getMinutesBack() {
        return minutesBack;
    }

    public void setMinutesBack(Integer minutesBack) {
        this.minutesBack = minutesBack;
    }

    public DashboardVisibility getVisibility() {
        return visibility == null ? DashboardVisibility.SHARED : visibility;
    }

    public void setVisibility(DashboardVisibility visibility) {
        this.visibility = visibility;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    /** Whether this user may see (and so edit) it. */
    boolean visibleTo(String userId) {
        return getVisibility() == DashboardVisibility.SHARED || (userId != null && userId.equals(ownerUserId));
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
