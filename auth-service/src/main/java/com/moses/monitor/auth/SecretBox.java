package com.moses.monitor.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets people give MoSes (the AI model API keys) before they are stored, with
 * AES-256-GCM under a key that exists only in this service's configuration
 * (SECRETS_ENCRYPTION_KEY). Reading the database alone does not reveal a key.
 *
 * Without SECRETS_ENCRYPTION_KEY the service still runs; it only refuses to store secrets.
 */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretBox(@Value("${moses.secrets.encryption-key:}") String secret) {
        if (secret == null || secret.length() < 32) {
            this.key = null;
            return;
        }
        try {
            // Any long random string works; it is stretched to exactly 256 bits.
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireKey();
        if (ciphertext == null || !ciphertext.startsWith("v1:")) {
            throw new IllegalArgumentException("not an encrypted value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong SECRETS_ENCRYPTION_KEY, or tampered data.
            throw new IllegalStateException("could not decrypt a stored secret", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("SECRETS_ENCRYPTION_KEY is not set");
        }
    }
}
