package com.moses.monitor.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Lets other MoSes services post into an environment's team chat as MoSes itself (today:
 * process-monitor announcing incidents). Internal only: never routed by api-gateway, and the
 * caller must present INTERNAL_API_TOKEN.
 */
@RestController
public class SystemMessageController {

    static final String SYSTEM_ID = "moses-system";
    static final String SYSTEM_NAME = "MoSes alerts";

    public record SystemPost(String projectId, String environment, String text, ChatMessage.SystemEvent event) { }

    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final ChatHub hub;
    private final byte[] internalToken;

    public SystemMessageController(org.springframework.data.mongodb.core.MongoTemplate mongoTemplate, ChatHub hub,
                                   @Value("${moses.internal.token:}") String internalToken) {
        this.mongoTemplate = mongoTemplate;
        this.hub = hub;
        this.internalToken = internalToken == null || internalToken.isBlank() ? null
                : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/chat/system")
    public ChatMessage post(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                            @RequestBody SystemPost body) {
        if (internalToken == null || token == null
                || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal only");
        }
        if (isBlank(body.projectId()) || isBlank(body.environment()) || isBlank(body.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId, environment and text are required");
        }
        String text = body.text().strip();
        ChatMessage message = new ChatMessage();
        message.setProjectId(body.projectId());
        message.setEnvironment(body.environment());
        message.setChannel(Channels.TEAM);
        message.setAuthorType(ChatMessage.AuthorType.SYSTEM);
        message.setAuthorId(SYSTEM_ID);
        message.setAuthorName(SYSTEM_NAME);
        message.setText(text.length() > ChatController.MAX_TEXT ? text.substring(0, ChatController.MAX_TEXT) : text);
        message.setEvent(body.event());
        message.setCreatedAt(Instant.now());
        mongoTemplate.insert(message);
        hub.publishMessage(message);
        return message;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
