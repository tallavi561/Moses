package com.moses.monitor.ai.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-project cap on agent runs per UTC day (chat answers and incident investigations share
 * it), so a loop or a busy day can't run up the project's bill.
 */
@Component
public class AgentQuota {

    private final int dailyLimit;
    private final Map<String, AtomicInteger> usedToday = new ConcurrentHashMap<>();

    public AgentQuota(@Value("${moses.agent.daily-replies-per-project:200}") int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public void count(String projectId) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (usedToday.size() > 10_000) {
            usedToday.keySet().removeIf(k -> !k.endsWith(today));
        }
        int used = usedToday.computeIfAbsent(projectId + "|" + today, k -> new AtomicInteger()).incrementAndGet();
        if (used > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "I've reached today's limit of " + dailyLimit + " answers for this project. It resets at midnight UTC.");
        }
    }
}
