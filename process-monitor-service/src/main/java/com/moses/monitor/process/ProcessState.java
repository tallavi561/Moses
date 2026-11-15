package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * How one service is doing in one environment of its project: status, metrics, and the probe
 * that may be watching it there. One document per (project, environment, service), upserted on
 * every heartbeat. What the service is - its name and description - lives in
 * {@link ServiceDefinition} and is shared by all environments; edges are {@link Connection}s.
 */
@Document(collection = "process_state")
public class ProcessState {

    /** {@link #idOf}: the three parts of the key, so a heartbeat is a single-document upsert. */
    @Id
    private String id;

    @Field("project_id")
    private String projectId;

    @Field("environment")
    private String environment;

    @Field("service_name")
    private String serviceName;

    @Field("status")
    private ProcessStatus status;

    @Field("cpu_percent")
    private Double cpuPercent;

    @Field("memory_mb")
    private Double memoryMb;

    @Field("last_seen")
    private Instant lastSeen;

    /** If set, {@link EndpointProber} polls this URL on a schedule and drives status from the result. */
    @Field("probe_url")
    private String probeUrl;

    @Field("last_probe_at")
    private Instant lastProbeAt;

    @Field("last_probe_status_code")
    private Integer lastProbeStatusCode;

    @Field("last_probe_latency_ms")
    private Long lastProbeLatencyMs;

    @Field("last_probe_error")
    private String lastProbeError;

    public ProcessState() {
    }

    public ProcessState(String projectId, String environment, String serviceName) {
        this.id = idOf(projectId, environment, serviceName);
        this.projectId = projectId;
        this.environment = environment;
        this.serviceName = serviceName;
    }

    /**
     * The document id. ingestion-service builds the same string to mark a service alive when
     * its logs arrive (ServiceLivenessTracker) - keep the two in step.
     */
    public static String idOf(String projectId, String environment, String serviceName) {
        return projectId + "|" + environment + "|" + serviceName;
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

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public ProcessStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessStatus status) {
        this.status = status;
    }

    public Double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(Double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public Double getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(Double memoryMb) {
        this.memoryMb = memoryMb;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getProbeUrl() {
        return probeUrl;
    }

    public void setProbeUrl(String probeUrl) {
        this.probeUrl = probeUrl;
    }

    public Instant getLastProbeAt() {
        return lastProbeAt;
    }

    public void setLastProbeAt(Instant lastProbeAt) {
        this.lastProbeAt = lastProbeAt;
    }

    public Integer getLastProbeStatusCode() {
        return lastProbeStatusCode;
    }

    public void setLastProbeStatusCode(Integer lastProbeStatusCode) {
        this.lastProbeStatusCode = lastProbeStatusCode;
    }

    public Long getLastProbeLatencyMs() {
        return lastProbeLatencyMs;
    }

    public void setLastProbeLatencyMs(Long lastProbeLatencyMs) {
        this.lastProbeLatencyMs = lastProbeLatencyMs;
    }

    public String getLastProbeError() {
        return lastProbeError;
    }

    public void setLastProbeError(String lastProbeError) {
        this.lastProbeError = lastProbeError;
    }
}
