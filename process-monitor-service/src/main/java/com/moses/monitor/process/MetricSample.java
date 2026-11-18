package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One CPU/memory reading from one heartbeat, kept so a chart can show a service's resource use
 * over time - {@link ProcessState} only ever holds the latest reading. A heartbeat that reports
 * neither metric writes no sample. Bounded by a TTL index (see {@link ScopeIndexInitializer}),
 * independent of the (longer) log retention window since heartbeats are far more frequent.
 */
@Document(collection = "metric_samples")
public class MetricSample {

    @Id
    private String id;

    @Field("project_id")
    private String projectId;

    @Field("environment")
    private String environment;

    @Field("service_name")
    private String serviceName;

    @Field("timestamp")
    private Instant timestamp;

    @Field("cpu_percent")
    private Double cpuPercent;

    @Field("memory_mb")
    private Double memoryMb;

    public MetricSample() {
    }

    public MetricSample(String projectId, String environment, String serviceName, Instant timestamp,
                        Double cpuPercent, Double memoryMb) {
        this.projectId = projectId;
        this.environment = environment;
        this.serviceName = serviceName;
        this.timestamp = timestamp;
        this.cpuPercent = cpuPercent;
        this.memoryMb = memoryMb;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Double getCpuPercent() {
        return cpuPercent;
    }

    public Double getMemoryMb() {
        return memoryMb;
    }
}
