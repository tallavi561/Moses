package com.moses.monitor.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names of the services in the asker's project environment, read over REST from process-monitor (ai-service
 * never touches Mongo). The LLM needs them so "the order service" can become the exact
 * `order-service`. The project comes from the request being served (the scope headers
 * api-gateway sets), so one project's service names never reach another's prompt.
 * Cached briefly per environment; on failure the last known list (possibly empty) is returned.
 */
@Component
public class ServiceCatalog {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalog.class);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_CACHED_SCOPES = 1_000;

    static final String PROJECT_HEADER = "X-Moses-Project-Id";
    static final String ENVIRONMENT_HEADER = "X-Moses-Environment";

    private record Cached(List<String> names, long fetchedAtNanos) { }

    private final RestTemplate restTemplate;
    private final String processMonitorUrl;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

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
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return List.of();
        }
        String projectId = attributes.getRequest().getHeader(PROJECT_HEADER);
        String environment = attributes.getRequest().getHeader(ENVIRONMENT_HEADER);
        if (projectId == null || environment == null) {
            return List.of();
        }

        // Services belong to one environment of a project, so that's the cache key.
        String key = projectId + "|" + environment;
        Cached hit = cache.get(key);
        if (hit != null && System.nanoTime() - hit.fetchedAtNanos() < TTL.toNanos()) {
            return hit.names();
        }
        try {
            HttpHeaders scope = new HttpHeaders();
            scope.set(PROJECT_HEADER, projectId);
            scope.set(ENVIRONMENT_HEADER, environment);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> states = restTemplate.exchange(processMonitorUrl + "/api/process/state",
                    HttpMethod.GET, new HttpEntity<>(scope), List.class).getBody();
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
            if (cache.size() >= MAX_CACHED_SCOPES) {
                cache.clear();
            }
            cache.put(key, new Cached(List.copyOf(names), System.nanoTime()));
            return names;
        } catch (Exception e) {
            log.warn("Could not load service names from process-monitor: {}", e.getClass().getSimpleName());
            return hit == null ? List.of() : hit.names();
        }
    }
}
