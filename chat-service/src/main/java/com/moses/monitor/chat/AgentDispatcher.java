package com.moses.monitor.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands a message addressed to the agent to ai-service and posts the answer back into the same
 * channel, as a reply to that message. Runs off the request thread: the sender gets their own
 * message back at once, and everyone sees "MoSes is typing" until the answer arrives.
 */
@Component
public class AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    static final String AGENT_ID = "moses-agent";
    static final String AGENT_NAME = "MoSes";

    private final MongoTemplate mongoTemplate;
    private final ChatHub hub;
    private final RestTemplate http;
    private final String aiServiceUrl;
    private final int contextMessages;
    private final String internalToken;

    public AgentDispatcher(MongoTemplate mongoTemplate, ChatHub hub,
                           @Value("${moses.ai-service.url}") String aiServiceUrl,
                           @Value("${moses.chat.agent-context-messages}") int contextMessages,
                           @Value("${moses.chat.agent-timeout-seconds}") int timeoutSeconds,
                           @Value("${moses.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
        this.mongoTemplate = mongoTemplate;
        this.hub = hub;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(30, timeoutSeconds) * 1000);
        this.http = new RestTemplate(factory);
        this.aiServiceUrl = aiServiceUrl.endsWith("/") ? aiServiceUrl.substring(0, aiServiceUrl.length() - 1) : aiServiceUrl;
        this.contextMessages = Math.max(1, contextMessages);
    }

    /** Starts the agent on {@code question}; returns immediately. */
    public void answerLater(ChatMessage question, ProjectAccess.ProjectInfo project, String environment) {
        Thread.ofVirtual().name("agent-" + question.getId()).start(() -> answer(question, project, environment));
    }

    private void answer(ChatMessage question, ProjectAccess.ProjectInfo project, String environment) {
        hub.publishTyping(question.getProjectId(), question.getEnvironment(), question.getChannel(), true);
        String text;
        boolean failed = false;
        try {
            text = ask(question, project, environment);
        } catch (HttpStatusCodeException e) {
            failed = true;
            text = explain(e);
            log.warn("agent failed for message {}: HTTP {}", question.getId(), e.getStatusCode().value());
        } catch (RuntimeException e) {
            failed = true;
            text = "I couldn't answer right now - the AI service didn't respond (" + e.getClass().getSimpleName() + ").";
            log.warn("agent failed for message {}: {}", question.getId(), e.toString());
        } finally {
            hub.publishTyping(question.getProjectId(), question.getEnvironment(), question.getChannel(), false);
        }

        ChatMessage reply = new ChatMessage();
        reply.setProjectId(question.getProjectId());
        reply.setEnvironment(question.getEnvironment());
        reply.setChannel(question.getChannel());
        reply.setAuthorType(ChatMessage.AuthorType.AGENT);
        reply.setAuthorId(AGENT_ID);
        reply.setAuthorName(AGENT_NAME);
        reply.setText(text == null || text.isBlank() ? "(no answer)" : text);
        reply.setFailed(failed);
        reply.setReplyTo(ChatController.preview(question));
        reply.setCreatedAt(Instant.now());
        mongoTemplate.insert(reply);
        hub.publishMessage(reply);
    }

    private String ask(ChatMessage question, ProjectAccess.ProjectInfo project, String environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectName", project.name());
        body.put("repoUrl", project.repoUrl());
        body.put("channel", Channels.isPrivate(question.getChannel()) ? "private" : "team");
        body.put("askerName", question.getAuthorName());
        body.put("question", question.getText());
        body.put("replyTo", question.getReplyTo());
        body.put("transcript", transcriptBefore(question));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Same headers api-gateway sets after verifying access: the agent's log and service
        // lookups are limited to this project and the environment the asker was looking at.
        headers.set("X-Moses-Project-Id", project.id());
        headers.set("X-Moses-Environment", environment);
        // ai-service answers only MoSes services holding the shared internal token.
        headers.set("X-Internal-Token", internalToken == null ? "" : internalToken);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = http.postForObject(aiServiceUrl + "/internal/agent/reply",
                new HttpEntity<>(body, headers), Map.class);
        Object answer = response == null ? null : response.get("text");
        return answer == null ? null : answer.toString();
    }

    /** The channel's messages before the question, oldest first. */
    private List<Map<String, Object>> transcriptBefore(ChatMessage question) {
        Query query = new Query(Criteria.where("project_id").is(question.getProjectId())
                .and("environment").is(question.getEnvironment())
                .and("channel").is(question.getChannel())
                .and("created_at").lt(question.getCreatedAt()))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(contextMessages);
        List<ChatMessage> earlier = new ArrayList<>(mongoTemplate.find(query, ChatMessage.class));
        Collections.reverse(earlier);
        List<Map<String, Object>> transcript = new ArrayList<>();
        for (ChatMessage m : earlier) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("author", m.getAuthorName());
            line.put("agent", m.getAuthorType() == ChatMessage.AuthorType.AGENT);
            line.put("at", m.getCreatedAt().toString());
            line.put("text", m.getText());
            transcript.add(line);
        }
        return transcript;
    }

    private static String explain(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        int at = body.indexOf("\"message\":\"");
        if (at >= 0) {
            int end = body.indexOf('"', at + 11);
            if (end > at) {
                return body.substring(at + 11, end);
            }
        }
        return "I couldn't answer right now (AI service answered " + e.getStatusCode().value() + ").";
    }
}
