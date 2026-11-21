package com.moses.monitor.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The key a monitored service sends with its logs and heartbeats. It belongs to exactly one
 * environment of one project, which is how incoming data gets an owner.
 *
 * The key is looked up by its SHA-256 (the document id) - a plain hash is enough, since the key
 * is 256 random bits. So the owner can show and copy it again later, it is also kept encrypted
 * ({@link SecretBox}); keys created before that have only the hash and cannot be shown again.
 */
@Document(collection = "api_keys")
public class ApiKey {

    @Id
    private String keyHash;

    @Indexed
    @Field("project_id")
    private String projectId;

    private String environment;

    /** The first characters of the key, kept so people can tell their keys apart. */
    private String prefix;

    /** The key itself, encrypted; null for keys created before keys could be shown again. */
    @Field("key_ciphertext")
    private String keyCiphertext;

    @Field("created_by")
    private String createdBy;

    @Field("created_at")
    private Instant createdAt;

    public static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getKeyCiphertext() { return keyCiphertext; }
    public void setKeyCiphertext(String keyCiphertext) { this.keyCiphertext = keyCiphertext; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
