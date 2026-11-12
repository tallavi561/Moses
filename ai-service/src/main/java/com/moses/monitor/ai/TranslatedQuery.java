package com.moses.monitor.ai;

import java.time.Instant;
import java.util.List;

public class TranslatedQuery {
    private final List<String> words;
    private final Instant from;
    private final Instant to;
    private final Integer logLevel;
    private final String service;
    private final String traceId;
    /** Which translator produced this: "llm" or "naive". */
    private final String translator;
    /** Why the rule-based fallback was used instead of the LLM, or null. */
    private final String fallbackReason;

    public TranslatedQuery(List<String> words, Instant from, Instant to, Integer logLevel) {
        this(words, from, to, logLevel, null, null, "naive", null);
    }

    public TranslatedQuery(List<String> words, Instant from, Instant to, Integer logLevel,
                           String service, String traceId, String translator, String fallbackReason) {
        this.words = words;
        this.from = from;
        this.to = to;
        this.logLevel = logLevel;
        this.service = service;
        this.traceId = traceId;
        this.translator = translator;
        this.fallbackReason = fallbackReason;
    }

    /** Same query, marked as the rule-based fallback with the reason the LLM wasn't used. */
    public TranslatedQuery asFallback(String reason) {
        return new TranslatedQuery(words, from, to, logLevel, service, traceId, "naive", reason);
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

    public String getService() {
        return service;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getTranslator() {
        return translator;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }
}
