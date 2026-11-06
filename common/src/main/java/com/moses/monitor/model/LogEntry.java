package com.moses.monitor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "logs")
public class LogEntry {

    @Id
    private String logId;

    @Field("timestamp")
    private Instant timestamp;

    @Field("service_name")
    private String serviceName;

    @Field("log_level")
    private int logLevel;

    @Field("ip")
    private String ip;

    @Field("raw_message")
    private String rawMessage;

    /**
     * Optional request-correlation fields: one traceId per logical request as it crosses
     * services, one spanId per hop within it. Not populated by any sender yet - added ahead
     * of real usage so adopting trace correlation later (via the SKILL, or real OpenTelemetry)
     * doesn't require a schema migration on live data. See VISION.md section 4.
     */
    @Field("trace_id")
    private String traceId;

    @Field("span_id")
    private String spanId;

    public LogEntry() {
    }

    public LogEntry(String logId, Instant timestamp, String serviceName, int logLevel, String ip, String rawMessage,
                     String traceId, String spanId) {
        this.logId = logId;
        this.timestamp = timestamp;
        this.serviceName = serviceName;
        this.logLevel = logLevel;
        this.ip = ip;
        this.rawMessage = rawMessage;
        this.traceId = traceId;
        this.spanId = spanId;
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }
}
