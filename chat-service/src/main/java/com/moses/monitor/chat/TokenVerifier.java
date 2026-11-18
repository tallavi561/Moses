package com.moses.monitor.chat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/** Verifies the login token auth-service issued, with the shared key. */
@Component
public class TokenVerifier {

    public record Identity(String userId, String name, String token) { }

    private final SecretKey key;

    public TokenVerifier(@Value("${moses.jwt.secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least 32 characters (same value as on auth-service).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Null when the token is missing, forged or expired. */
    public Identity verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token.trim()).getPayload();
            Object name = claims.get("name");
            return new Identity(claims.getSubject(), name == null ? "?" : name.toString(), token.trim());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Same, from an "Authorization: Bearer ..." header value. */
    public Identity fromHeader(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return verify(authorization.substring(7));
    }
}
