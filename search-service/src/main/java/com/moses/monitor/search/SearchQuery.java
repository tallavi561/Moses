package com.moses.monitor.search;

import java.time.Instant;
import java.util.List;

public class SearchQuery {

    private final List<String> words;
    private final Instant from;
    private final Instant to;
    private final Integer logLevel;
    private final String serviceName;
    private final String traceId;

    public SearchQuery(List<String> words, Instant from, Instant to, Integer logLevel, String serviceName) {
        this(words, from, to, logLevel, serviceName, null);
    }

    public SearchQuery(List<String> words, Instant from, Instant to, Integer logLevel, String serviceName,
                       String traceId) {
        this.words = words;
        this.from = from;
        this.to = to;
        this.logLevel = logLevel;
        this.serviceName = serviceName == null || serviceName.isBlank() ? null : serviceName.trim();
        this.traceId = traceId == null || traceId.isBlank() ? null : traceId.trim();
    }

    /** Exact traceId to restrict results to, or null. */
    public String getTraceId() {
        return traceId;
    }

    public List<String> getWords() {
        return words;
    }

    public Instant getFrom() {
        return from;
    }

    public Instant getTo() {
        return to;
    }

    public Integer getLogLevel() {
        return logLevel;
    }

    /** Exact service name to restrict results to, or null for all services. */
    public String getServiceName() {
        return serviceName;
    }
}
