package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

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

    @Field("created_by")
    private String createdBy;

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
