package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * When a service's errors count as an incident. One rule per service at most; a service without
 * one uses the built-in defaults ({@link #DEFAULT_MIN_ERRORS}, {@link #DEFAULT_FACTOR}).
 *
 * An error spike needs both: at least {@code minErrors} errors in the window (so one stray error
 * on a quiet service is not an incident) and at least {@code factor} times the service's normal
 * amount (so a service that always logs some errors is judged against itself).
 */
@Document(collection = "alert_rules")
public class AlertRule {

    public static final int DEFAULT_MIN_ERRORS = 5;
    public static final double DEFAULT_FACTOR = 3.0;

    @Id
    private String id;
    @Field("project_id")
    private String projectId;
    private String environment;
    @Field("service_name")
    private String serviceName;
    @Field("min_errors")
    private int minErrors = DEFAULT_MIN_ERRORS;
    private double factor = DEFAULT_FACTOR;
    /** No incidents at all for this service (neither error spikes nor DOWN). */
    private boolean muted;

    static AlertRule defaults(String serviceName) {
        AlertRule rule = new AlertRule();
        rule.setServiceName(serviceName);
        return rule;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public int getMinErrors() { return minErrors; }
    public void setMinErrors(int minErrors) { this.minErrors = minErrors; }
    public double getFactor() { return factor; }
    public void setFactor(double factor) { this.factor = factor; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean muted) { this.muted = muted; }
}
