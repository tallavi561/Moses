package com.moses.monitor.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Is this user in this project?" - asked of auth-service with the user's own token, so the
 * answer is exactly what the user may see. Checked here as well as at the gateway because the
 * WebSocket reaches this service directly. Cached briefly per user and project.
 */
@Component
public class ProjectAccess {

    /** What the chat needs to know about a project the user is in. */
    public record ProjectInfo(String id, String name, String role, String repoUrl, List<String> environments) { }

    public static class UnavailableException extends RuntimeException {
        UnavailableException(Throwable cause) {
            super("auth-service unreachable", cause);
        }
    }

    private record Cached(ProjectInfo info, long expiresAtMs) { }

    private static final long TTL_MS = 30_000;
    private static final int MAX_CACHED = 10_000;

    private final RestTemplate http;
    private final String authServiceUrl;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ProjectAccess(@Value("${moses.auth-service.url}") String authServiceUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.http = new RestTemplate(factory);
        this.authServiceUrl = authServiceUrl.endsWith("/")
                ? authServiceUrl.substring(0, authServiceUrl.length() - 1) : authServiceUrl;
    }

    /** The project, or null when the user is not a member of it. */
    public ProjectInfo find(TokenVerifier.Identity user, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        String cacheKey = user.userId() + "|" + projectId;
        long now = System.currentTimeMillis();
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.info();
        }

        List<Map<String, Object>> projects;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(user.token());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = http.exchange(authServiceUrl + "/api/projects", HttpMethod.GET,
                    new HttpEntity<>(headers), List.class).getBody();
            projects = body == null ? List.of() : body;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return null;
        } catch (RuntimeException e) {
            throw new UnavailableException(e);
        }

        ProjectInfo found = null;
        for (Map<String, Object> p : projects) {
            if (projectId.equals(p.get("id"))) {
                @SuppressWarnings("unchecked")
                List<String> environments = p.get("environments") instanceof List<?> list
                        ? (List<String>) list : List.of();
                found = new ProjectInfo(projectId, str(p.get("name")), str(p.get("role")), str(p.get("repoUrl")),
                        environments);
            }
        }
        if (found != null) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(cacheKey, new Cached(found, now + TTL_MS));
        } else {
            cache.remove(cacheKey);
        }
        return found;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
