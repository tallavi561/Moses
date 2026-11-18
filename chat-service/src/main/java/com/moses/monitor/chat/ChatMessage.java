package com.moses.monitor.chat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One chat message. Every environment of a project has its own chat (like everything else in
 * it), and inside it {@code channel} is {@link Channels#TEAM} (everyone in the project) or
 * {@code agent:<userId>} (one person's private conversation with the agent).
 */
@Document(collection = "chat_messages")
@CompoundIndex(name = "project_environment_channel_time",
        def = "{'project_id': 1, 'environment': 1, 'channel': 1, 'created_at': -1}")
public class ChatMessage {

    public enum AuthorType { USER, AGENT }

    /** Enough of the message being answered to show it above the reply without another lookup. */
    public record ReplyPreview(String id, String authorName, AuthorType authorType, String text) { }

    @Id
    private String id;
    @Field("project_id")
    private String projectId;
    private String environment;
    private String channel;
    @Field("author_type")
    private AuthorType authorType;
    @Field("author_id")
    private String authorId;
    @Field("author_name")
    private String authorName;
    private String text;
    @Field("reply_to")
    private ReplyPreview replyTo;
    /** True when the agent could not answer and the text explains why. */
    private boolean failed;
    @Field("created_at")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public AuthorType getAuthorType() { return authorType; }
    public void setAuthorType(AuthorType authorType) { this.authorType = authorType; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public ReplyPreview getReplyTo() { return replyTo; }
    public void setReplyTo(ReplyPreview replyTo) { this.replyTo = replyTo; }
    public boolean isFailed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
