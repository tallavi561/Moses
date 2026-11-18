package com.moses.monitor.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretBoxTest {

    private static final String KEY = "test-encryption-key-test-encryption-key-123";

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        SecretBox box = new SecretBox(KEY);
        String sealed = box.encrypt("sk-ant-secret-value");
        assertFalse(sealed.contains("sk-ant"));
        assertEquals("sk-ant-secret-value", box.decrypt(sealed));
        assertNotEquals(sealed, box.encrypt("sk-ant-secret-value"), "a fresh IV every time");
    }

    @Test
    void anotherKeyCannotRead() {
        String sealed = new SecretBox(KEY).encrypt("sk-ant-secret-value");
        assertThrows(IllegalStateException.class,
                () -> new SecretBox("a-completely-different-key-a-completely-different").decrypt(sealed));
    }

    @Test
    void withoutAKeyItRefusesInsteadOfStoringPlaintext() {
        SecretBox box = new SecretBox("");
        assertFalse(box.isConfigured());
        assertThrows(IllegalStateException.class, () -> box.encrypt("x"));
        assertTrue(new SecretBox(KEY).isConfigured());
    }
}
