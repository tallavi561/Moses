package com.moses.monitor.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Path-prefix -> backend base URL. Order matters: first matching prefix wins,
 * so register the most specific prefixes first if that ever changes.
 */
@Component
public class RouteConfig {

    private final Map<String, String> routes = new LinkedHashMap<>();

    public RouteConfig(@Value("${moses.routes.ingestion-service}") String ingestionUrl,
                        @Value("${moses.routes.search-service}") String searchUrl,
                        @Value("${moses.routes.process-monitor-service}") String processUrl,
                        @Value("${moses.routes.ai-service}") String aiUrl) {
        routes.put("/api/logs", ingestionUrl);
        routes.put("/api/search", searchUrl);
        routes.put("/api/process", processUrl);
        routes.put("/api/ai", aiUrl);
    }

    /** Returns the backend base URL for the given request path, or null if no prefix matches. */
    public String resolve(String path) {
        for (Map.Entry<String, String> entry : routes.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
