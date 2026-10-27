package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Latest known state of one monitored node (service/process). One document
 * per serviceName, upserted on every heartbeat. This is what the React flow
 * diagram reads to draw nodes; edges come from the separate Connection
 * documents (see {@link Connection}).
 */
@Document(collection = "process_state")
public class ProcessState {

    @Id
    private String serviceName;

    @Field("process_name")
    private String processName;

    @Field("description")
    private String description;

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

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getProcessName() {
        return processName;
    }

    public void setProcessName(String processName) {
        this.processName = processName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
