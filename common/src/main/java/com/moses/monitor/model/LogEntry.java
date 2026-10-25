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

    public LogEntry() {
    }

    public LogEntry(String logId, Instant timestamp, String serviceName, int logLevel, String ip, String rawMessage) {
        this.logId = logId;
        this.timestamp = timestamp;
        this.serviceName = serviceName;
        this.logLevel = logLevel;
        this.ip = ip;
        this.rawMessage = rawMessage;
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
}
