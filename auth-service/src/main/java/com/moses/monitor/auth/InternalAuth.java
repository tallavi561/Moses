package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the /internal endpoints other MoSes services call (never proxied by api-gateway): the
 * caller must present INTERNAL_API_TOKEN, a secret shared only between the services. Without it
 * configured, internal endpoints are closed.
 */
@Component
public class InternalAuth {

    static final String HEADER = "X-Internal-Token";

    private final byte[] token;

    public InternalAuth(@Value("${moses.internal.token:}") String token) {
        this.token = token == null || token.length() < 32 ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public void require(HttpServletRequest request) {
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_API_TOKEN is not configured");
        }
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal only");
        }
    }

    public String headerValue() {
        return token == null ? null : new String(token, StandardCharsets.UTF_8);
    }
}
