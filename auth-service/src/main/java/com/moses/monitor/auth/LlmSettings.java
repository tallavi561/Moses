package com.moses.monitor.auth;

import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Which language model the project's AI agent uses, and the API key it pays with. Set on the
 * project (the default for all its environments) and, optionally, on one environment to
 * override it. The key is stored encrypted ({@link SecretBox}) and never sent back to a browser;
 * {@code keyHint} (its last 4 characters) is what people see.
 */
public class LlmSettings {

    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";

    private String provider;
    private String model;
    /** Only for openai-compatible: the API root, e.g. https://api.openai.com/v1 */
    @Field("base_url")
    private String baseUrl;
    @Field("key_ciphertext")
    private String keyCiphertext;
    @Field("key_hint")
    private String keyHint;
    @Field("updated_at")
    private Instant updatedAt;
    @Field("updated_by")
    private String updatedBy;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getKeyCiphertext() { return keyCiphertext; }
    public void setKeyCiphertext(String keyCiphertext) { this.keyCiphertext = keyCiphertext; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
