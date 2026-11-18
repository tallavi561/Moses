package com.moses.monitor.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reading and sending chat messages. Reached through api-gateway like every other /api path;
 * the user and their membership are still checked here (see {@link ProjectAccess}). Each
 * environment of a project has its own chat: the request's environment picks which one.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final int MAX_TEXT = 4000;
    private static final int MAX_PAGE = 100;
    private static final int PREVIEW_CHARS = 160;

    public record SendRequest(@NotBlank @Size(max = MAX_TEXT) String text,
                              @NotBlank String channel,
                              String replyToId) { }

    public record Page(List<ChatMessage> messages, boolean hasMore) { }

    private final MongoTemplate mongoTemplate;
    private final TokenVerifier tokens;
    private final ProjectAccess access;
    private final ChatHub hub;
    private final AgentDispatcher agent;

    public ChatController(MongoTemplate mongoTemplate, TokenVerifier tokens, ProjectAccess access,
                          ChatHub hub, AgentDispatcher agent) {
        this.mongoTemplate = mongoTemplate;
        this.tokens = tokens;
        this.access = access;
        this.hub = hub;
        this.agent = agent;
    }

    /** Newest {@code limit} messages before {@code before} (or the newest of all), returned oldest first. */
    @GetMapping("/messages")
    public Page messages(HttpServletRequest request,
                         @RequestParam String channel,
                         @RequestParam(required = false) Instant before,
                         @RequestParam(defaultValue = "50") int limit) {
        TokenVerifier.Identity user = requireUser(request);
        ProjectAccess.ProjectInfo project = requireProject(request, user);
        String environment = requireEnvironment(request, project);
        requireChannel(channel, user);

        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        Criteria criteria = Criteria.where("project_id").is(project.id()).and("environment").is(environment)
                .and("channel").is(channel);
        if (before != null) {
            criteria = criteria.and("created_at").lt(before);
        }
        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "created_at")).limit(size + 1);
        List<ChatMessage> found = new ArrayList<>(mongoTemplate.find(query, ChatMessage.class));
        boolean hasMore = found.size() > size;
        if (hasMore) {
            found = new ArrayList<>(found.subList(0, size));
        }
        Collections.reverse(found);
        return new Page(found, hasMore);
    }

    @PostMapping("/messages")
    public ChatMessage send(HttpServletRequest request, @Valid @RequestBody SendRequest body) {
        TokenVerifier.Identity user = requireUser(request);
        ProjectAccess.ProjectInfo project = requireProject(request, user);
        String environment = requireEnvironment(request, project);
        requireChannel(body.channel(), user);

        ChatMessage.ReplyPreview replyTo = null;
        if (body.replyToId() != null && !body.replyToId().isBlank()) {
            ChatMessage original = mongoTemplate.findById(body.replyToId(), ChatMessage.class);
            if (original == null || !original.getProjectId().equals(project.id())
                    || !environment.equals(original.getEnvironment())
                    || !original.getChannel().equals(body.channel())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the message being replied to is not in this chat");
            }
            replyTo = preview(original);
        }

        ChatMessage message = new ChatMessage();
        message.setProjectId(project.id());
        message.setEnvironment(environment);
        message.setChannel(body.channel());
        message.setAuthorType(ChatMessage.AuthorType.USER);
        message.setAuthorId(user.userId());
        message.setAuthorName(user.name());
        message.setText(body.text().strip());
        message.setReplyTo(replyTo);
        message.setCreatedAt(Instant.now());
        mongoTemplate.insert(message);
        hub.publishMessage(message);

        if (Channels.addressesAgent(message.getChannel(), message.getText(), replyTo)) {
            agent.answerLater(message, project, environment);
        }
        return message;
    }

    /** Who is in the conversation: used by the client to tell people apart. */
    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        TokenVerifier.Identity user = requireUser(request);
        ProjectAccess.ProjectInfo project = requireProject(request, user);
        return Map.of("userId", user.userId(), "privateChannel", Channels.privateWithAgent(user.userId()),
                "projectId", project.id(), "environment", requireEnvironment(request, project));
    }

    static ChatMessage.ReplyPreview preview(ChatMessage m) {
        String text = m.getText() == null ? "" : m.getText();
        if (text.length() > PREVIEW_CHARS) {
            text = text.substring(0, PREVIEW_CHARS) + "...";
        }
        return new ChatMessage.ReplyPreview(m.getId(), m.getAuthorName(), m.getAuthorType(), text);
    }

    private TokenVerifier.Identity requireUser(HttpServletRequest request) {
        TokenVerifier.Identity user = tokens.fromHeader(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not signed in");
        }
        return user;
    }

    /** The project comes from the gateway's verified scope header, and is re-checked against auth-service. */
    private ProjectAccess.ProjectInfo requireProject(HttpServletRequest request, TokenVerifier.Identity user) {
        String projectId = request.getHeader("X-Moses-Project-Id");
        if (projectId == null || projectId.isBlank()) {
            projectId = request.getHeader("X-Project-Id");
        }
        ProjectAccess.ProjectInfo project;
        try {
            project = access.find(user, projectId);
        } catch (ProjectAccess.UnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "could not verify access, try again");
        }
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
        }
        return project;
    }

    private static void requireChannel(String channel, TokenVerifier.Identity user) {
        if (!Channels.mayUse(channel, user.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chat not found");
        }
    }

    /** Which environment's chat: the one api-gateway verified, and it must exist in the project. */
    private static String requireEnvironment(HttpServletRequest request, ProjectAccess.ProjectInfo project) {
        String env = request.getHeader("X-Moses-Environment");
        if (env == null || env.isBlank()) {
            env = request.getHeader("X-Environment");
        }
        if (env == null || !project.environments().contains(env)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "environment not found");
        }
        return env;
    }
}
