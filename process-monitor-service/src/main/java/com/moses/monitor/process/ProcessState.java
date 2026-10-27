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

    @Field("status")
    private ProcessStatus status;

    @Field("cpu_percent")
    private Double cpuPercent;

    @Field("memory_mb")
    private Double memoryMb;

    @Field("last_seen")
    private Instant lastSeen;

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
}
