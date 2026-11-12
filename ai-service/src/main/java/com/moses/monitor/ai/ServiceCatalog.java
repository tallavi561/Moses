package com.moses.monitor.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Names of the services MoSes knows about, read over REST from process-monitor (ai-service never
 * touches Mongo). The LLM needs them so "the order service" can become the exact `order-service`.
 * Cached briefly; on failure the last known list (possibly empty) is returned.
 */
@Component
public class ServiceCatalog {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalog.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final RestTemplate restTemplate;
    private final String processMonitorUrl;

    private volatile List<String> cached = List.of();
    private volatile long fetchedAtNanos = 0;
    private volatile boolean everFetched = false;

    public ServiceCatalog(@Value("${moses.process-monitor.url:}") String processMonitorUrl) {
        // Own short timeouts: a sleeping/unreachable process-monitor must not stall a question.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.restTemplate = new RestTemplate(factory);
        this.processMonitorUrl = Urls.normalize(processMonitorUrl);
    }

    public List<String> serviceNames() {
        if (processMonitorUrl == null || processMonitorUrl.isBlank()) {
            return List.of();
        }
        boolean fresh = everFetched && System.nanoTime() - fetchedAtNanos < TTL.toNanos();
        if (fresh) {
            return cached;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> states = restTemplate.getForObject(processMonitorUrl + "/api/process/state", List.class);
            List<String> names = new ArrayList<>();
            if (states != null) {
                for (Map<String, Object> state : states) {
                    Object name = state.get("serviceName");
                    if (name instanceof String s && !s.isBlank()) {
                        names.add(s);
                    }
                }
            }
            names.sort(String::compareTo);
            cached = List.copyOf(names);
            everFetched = true;
            fetchedAtNanos = System.nanoTime();
        } catch (Exception e) {
            log.warn("Could not load service names from process-monitor: {}", e.getClass().getSimpleName());
        }
        return cached;
    }
}
