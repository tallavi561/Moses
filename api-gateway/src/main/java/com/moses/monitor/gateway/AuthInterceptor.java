package com.moses.monitor.gateway;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Every /api/** request must carry a valid login token (issued by auth-service, verified here
 * with the shared key - no call to auth-service per request), except the short public list in
 * {@link #isPublic}.
 *
 * Monitoring requests ({@link #isScoped}) need more than a token: every log, service and
 * connection belongs to one environment of one project, so the request must say which one and
 * be allowed in it. A signed-in user names it with X-Project-Id / X-Environment and must be a
 * member (a VIEWER cannot change anything); a monitored service proves it with X-Api-Key, which
 * only ever lets it send logs and heartbeats. Either way the verified answer is left on the
 * request for {@link ProxyController} to pass downstream.
 *
 * An interceptor rather than a servlet filter on purpose: Spring MVC applies the CORS headers
 * before interceptors run, so the browser can actually read this 401 instead of reporting an
 * opaque CORS failure.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    static final String SCOPE_ATTRIBUTE = "moses.scope";

    private final SecretKey key;
    private final ScopeResolver scopeResolver;

    public AuthInterceptor(@Value("${moses.jwt.secret}") String secret, ScopeResolver scopeResolver) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least 32 characters (same value as on auth-service).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.scopeResolver = scopeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (isPublic(method, path)) {
            return true;
        }
        if (isScoped(path)) {
            return authorizeScoped(request, response, method, path);
        }
        if (userIdOf(request) != null) {
            return true;
        }
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "not signed in");
    }

    private boolean authorizeScoped(HttpServletRequest request, HttpServletResponse response,
                                    String method, String path) throws IOException {
        ScopeResolver.Scope scope;
        try {
            String apiKey = request.getHeader("X-Api-Key");
            if (apiKey != null && !apiKey.isBlank()) {
                if (!acceptsApiKey(method, path)) {
                    return reject(response, HttpServletResponse.SC_FORBIDDEN,
                            "an API key can only send logs and heartbeats");
                }
                scope = scopeResolver.forApiKey(apiKey.trim());
                if (scope == null) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unknown API key");
                }
            } else {
                String userId = userIdOf(request);
                if (userId == null) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                            acceptsApiKey(method, path) ? "an API key (X-Api-Key) is required" : "not signed in");
                }
                String projectId = request.getHeader("X-Project-Id");
                String environment = request.getHeader("X-Environment");
                if (projectId == null || projectId.isBlank() || environment == null || environment.isBlank()) {
                    return reject(response, HttpServletResponse.SC_BAD_REQUEST,
                            "X-Project-Id and X-Environment are required");
                }
                scope = scopeResolver.forUser(request.getHeader(HttpHeaders.AUTHORIZATION), userId,
                        projectId.trim(), environment.trim());
                if (scope == null) {
                    return reject(response, HttpServletResponse.SC_NOT_FOUND, "project or environment not found");
                }
                if (scope.readOnly() && isChange(method, path)) {
                    return reject(response, HttpServletResponse.SC_FORBIDDEN, "viewers cannot make changes");
                }
            }
        } catch (ScopeResolver.UnavailableException e) {
            return reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "could not verify access, try again");
        }
        request.setAttribute(SCOPE_ATTRIBUTE, scope);
        return true;
    }

    static boolean isPublic(String method, String path) {
        // CORS preflight never carries credentials.
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            return path.equals("/api/auth/login") || path.equals("/api/auth/register");
        }
        if ("GET".equalsIgnoreCase(method)) {
            // Invite preview only: /api/invites/{token}, nothing deeper.
            return path.startsWith("/api/invites/") && path.indexOf('/', "/api/invites/".length()) < 0;
        }
        return false;
    }

    /** Monitoring data: everything that belongs to one environment of one project. */
    static boolean isScoped(String path) {
        return under(path, "/api/logs") || under(path, "/api/search")
                || under(path, "/api/process") || under(path, "/api/ai");
    }

    /** The two things a monitored service does: send a log, report a heartbeat. */
    static boolean acceptsApiKey(String method, String path) {
        return "POST".equalsIgnoreCase(method)
                && (path.equals("/api/logs") || path.equals("/api/process/heartbeat"));
    }

    /** Requests that change monitoring data. Asking the AI a question is a POST but only reads. */
    static boolean isChange(String method, String path) {
        return !"GET".equalsIgnoreCase(method) && (under(path, "/api/logs") || under(path, "/api/process"));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
        return false;
    }

    /** The signed-in user's id, or null without a valid login token. */
    private String userIdOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(header.substring(7).trim()).getPayload().getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
