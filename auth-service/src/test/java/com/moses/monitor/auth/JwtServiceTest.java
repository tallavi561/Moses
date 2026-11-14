package com.moses.monitor.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    private static User user() {
        User user = new User();
        user.setId("u1");
        user.setName("Dana");
        user.setEmail("dana@example.com");
        return user;
    }

    @Test
    void issuedTokenVerifiesBackToTheUserId() {
        JwtService jwt = new JwtService(SECRET, 1);
        assertEquals("u1", jwt.verify(jwt.issue(user())));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = new JwtService("another-secret-another-secret-another-1", 1).issue(user());
        assertNull(new JwtService(SECRET, 1).verify(forged));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, -1);
        assertNull(jwt.verify(jwt.issue(user())));
    }

    @Test
    void garbageAndMissingTokensAreRejected() {
        JwtService jwt = new JwtService(SECRET, 1);
        assertNull(jwt.verify(null));
        assertNull(jwt.verify(""));
        assertNull(jwt.verify("not.a.token"));
    }

    @Test
    void refusesToStartWithoutARealSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService("", 1));
        assertThrows(IllegalStateException.class, () -> new JwtService("short", 1));
    }
}
