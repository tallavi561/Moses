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
 * An interceptor rather than a servlet filter on purpose: Spring MVC applies the CORS headers
 * before interceptors run, so the browser can actually read this 401 instead of reporting an
 * opaque CORS failure.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SecretKey key;

    public AuthInterceptor(@Value("${moses.jwt.secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least 32 characters (same value as on auth-service).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isPublic(request.getMethod(), request.getRequestURI()) || hasValidToken(request)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"message\":\"not signed in\"}");
        return false;
    }

    static boolean isPublic(String method, String path) {
        // CORS preflight never carries credentials.
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            return path.equals("/api/auth/login")
                    || path.equals("/api/auth/register")
                    // Monitored services report in without a user session. Still open, as it
                    // was before sign-in existed; a per-project API key is a separate step.
                    || path.equals("/api/logs")
                    || path.equals("/api/process/heartbeat");
        }
        if ("GET".equalsIgnoreCase(method)) {
            // Invite preview only: /api/invites/{token}, nothing deeper.
            return path.startsWith("/api/invites/") && path.indexOf('/', "/api/invites/".length()) < 0;
        }
        return false;
    }

    private boolean hasValidToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(7).trim());
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
