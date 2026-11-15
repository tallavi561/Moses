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
 * Only the SHA-256 of the key is stored (as the document id), so the database never holds a
 * usable key: it is shown once, when created. A plain hash is enough here - unlike a password,
 * the key is 256 random bits, so there is nothing to brute-force.
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
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
