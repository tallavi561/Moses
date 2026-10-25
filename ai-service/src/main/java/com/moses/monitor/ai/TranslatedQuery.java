package com.moses.monitor.ai;

import java.time.Instant;
import java.util.List;

public class TranslatedQuery {
    private final List<String> words;
    private final Instant from;
    private final Instant to;
    private final Integer logLevel;

    public TranslatedQuery(List<String> words, Instant from, Instant to, Integer logLevel) {
        this.words = words;
        this.from = from;
        this.to = to;
        this.logLevel = logLevel;
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
}
