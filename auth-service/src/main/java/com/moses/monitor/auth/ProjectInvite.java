package com.moses.monitor.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * An invite link: whoever holds the token can join the project until it expires. The token is
 * the document id; MongoDB deletes the document by itself once {@code expiresAt} passes.
 */
@Document(collection = "project_invites")
public class ProjectInvite {

    @Id
    private String token;

    @Indexed
    @Field("project_id")
    private String projectId;

    @Field("created_by")
    private String createdBy;

    @Indexed(expireAfterSeconds = 0)
    @Field("expires_at")
    private Instant expiresAt;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
