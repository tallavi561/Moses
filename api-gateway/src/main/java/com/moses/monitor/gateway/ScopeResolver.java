package com.moses.monitor.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks auth-service which project and environment a monitoring request belongs to - either
 * "is this signed-in user in this project, and with what role?" or "whose API key is this?".
 * Answers are cached briefly, so a role change, a removed member or a revoked key takes up to
 * {@link #TTL_MS} to take effect here.
 */
@Component
public class ScopeResolver {

    /** Who owns the data a request reads or writes. {@code role} is null for an API key. */
    public record Scope(String projectId, String environment, String role) {
        boolean readOnly() {
            return "VIEWER".equals(role);
        }
    }

    /** auth-service could not be asked, as opposed to it answering "no". */
    public static class UnavailableException extends RuntimeException {
        UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Cached(Scope scope, long expiresAtMs) { }

    static final long TTL_MS = 30_000;
    private static final int MAX_CACHED = 10_000;

    private final RestTemplate restTemplate;
    private final String authServiceUrl;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ScopeResolver(RestTemplate restTemplate, @Value("${moses.routes.auth-service}") String authServiceUrl) {
        this.restTemplate = restTemplate;
        this.authServiceUrl = RouteConfig.normalize(authServiceUrl);
    }

    /** Null when the user is not in the project or the project has no such environment. */
    public Scope forUser(String authorizationHeader, String userId, String projectId, String environment) {
        return cached("u:" + userId + "|" + projectId + "|" + environment, () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
            java.net.URI uri = UriComponentsBuilder.fromHttpUrl(authServiceUrl)
                    .pathSegment("api", "projects", projectId, "access")
                    .queryParam("environment", environment)
                    .build().encode().toUri();
            return ask(uri, HttpMethod.GET, new HttpEntity<>(headers));
        });
    }

    /** Null when no such API key exists (never issued, or revoked). */
    public Scope forApiKey(String apiKey) {
        return cached("k:" + sha256(apiKey), () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            java.net.URI uri = java.net.URI.create(authServiceUrl + "/api/keys/resolve");
            return ask(uri, HttpMethod.POST, new HttpEntity<>(Map.of("key", apiKey), headers));
        });
    }

    private Scope cached(String cacheKey, java.util.function.Supplier<Scope> lookup) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.scope();
        }
        Scope scope = lookup.get();
        if (scope != null) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(cacheKey, new Cached(scope, now + TTL_MS));
        } else {
            cache.remove(cacheKey);
        }
        return scope;
    }

    private Scope ask(java.net.URI uri, HttpMethod method, HttpEntity<?> entity) {
        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(uri, method, entity, Map.class);
        } catch (RuntimeException e) {
            throw new UnavailableException("auth-service unreachable", e);
        }
        int status = response.getStatusCode().value();
        if (status >= 500) {
            throw new UnavailableException("auth-service answered " + status, null);
        }
        Map<?, ?> body = response.getBody();
        if (status != 200 || body == null || body.get("projectId") == null || body.get("environment") == null) {
            return null;
        }
        Object role = body.get("role");
        return new Scope(body.get("projectId").toString(), body.get("environment").toString(),
                role == null ? null : role.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
