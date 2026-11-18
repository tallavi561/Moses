package com.moses.monitor.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live side of the chat. A client opens {@code /ws/chat} and, as its first message, signs in:
 * {@code {"type":"auth","token":"...","projectId":"...","environment":"prod"}}. From then on it
 * receives every new message of that environment's chat it may see (the team channel, and its
 * own private channel with the agent) and the agent's "typing" state. Sending stays a plain REST call through api-gateway,
 * so this socket never accepts content - it only pushes.
 *
 * The token travels inside the socket, not in the URL, so it never lands in access logs.
 */
@Component
public class ChatHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHub.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

    /** A signed-in socket: who it is and which environment's chat it follows. */
    private record Subscriber(WebSocketSession session, String userId, String room) { }

    private final TokenVerifier tokens;
    private final ProjectAccess access;
    private final ObjectMapper json;

    /** "projectId|environment" -> its open, signed-in sockets. */
    private final Map<String, Set<Subscriber>> byRoom = new ConcurrentHashMap<>();
    /** session id -> subscriber, to clean up on close. */
    private final Map<String, Subscriber> bySession = new ConcurrentHashMap<>();

    public ChatHub(TokenVerifier tokens, ProjectAccess access, ObjectMapper json) {
        this.tokens = tokens;
        this.access = access;
        this.json = json;
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        JsonNode node;
        try {
            node = json.readTree(message.getPayload());
        } catch (IOException e) {
            raw.close(CloseStatus.BAD_DATA);
            return;
        }
        String type = node.path("type").asText("");
        if ("ping".equals(type)) {
            return;
        }
        if (!"auth".equals(type) || bySession.containsKey(raw.getId())) {
            return;
        }

        TokenVerifier.Identity user = tokens.verify(node.path("token").asText(null));
        if (user == null) {
            sendNow(raw, Map.of("type", "error", "message", "not signed in"));
            raw.close(new CloseStatus(4401, "not signed in"));
            return;
        }
        String projectId = node.path("projectId").asText(null);
        ProjectAccess.ProjectInfo project;
        try {
            project = access.find(user, projectId);
        } catch (ProjectAccess.UnavailableException e) {
            sendNow(raw, Map.of("type", "error", "message", "could not verify access, try again"));
            raw.close(new CloseStatus(4503, "auth unavailable"));
            return;
        }
        String environment = node.path("environment").asText(null);
        if (project == null || environment == null || !project.environments().contains(environment)) {
            sendNow(raw, Map.of("type", "error", "message", "project or environment not found"));
            raw.close(new CloseStatus(4404, "not found"));
            return;
        }

        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        Subscriber subscriber = new Subscriber(session, user.userId(), room(project.id(), environment));
        bySession.put(raw.getId(), subscriber);
        byRoom.computeIfAbsent(subscriber.room(), k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        send(subscriber, Map.of("type", "ready"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Subscriber subscriber = bySession.remove(session.getId());
        if (subscriber != null) {
            Set<Subscriber> set = byRoom.get(subscriber.room());
            if (set != null) {
                set.remove(subscriber);
                if (set.isEmpty()) {
                    byRoom.remove(subscriber.room(), set);
                }
            }
        }
    }

    /** A new message: to everyone in that environment's chat for the team channel, else to its owner only. */
    public void publishMessage(ChatMessage message) {
        publish(room(message.getProjectId(), message.getEnvironment()), message.getChannel(),
                Map.of("type", "message", "message", message));
    }

    public void publishTyping(String projectId, String environment, String channel, boolean typing) {
        publish(room(projectId, environment), channel, Map.of("type", "typing", "channel", channel, "typing", typing));
    }

    private static String room(String projectId, String environment) {
        return projectId + "|" + environment;
    }

    private void publish(String room, String channel, Object event) {
        Set<Subscriber> subscribers = byRoom.get(room);
        if (subscribers == null) {
            return;
        }
        String onlyFor = Channels.ownerOf(channel);
        for (Subscriber s : subscribers) {
            if (onlyFor == null || onlyFor.equals(s.userId())) {
                send(s, event);
            }
        }
    }

    private void send(Subscriber subscriber, Object event) {
        try {
            subscriber.session().sendMessage(new TextMessage(json.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            log.debug("dropping a closed or slow chat socket: {}", e.getMessage());
            try {
                subscriber.session().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    private void sendNow(WebSocketSession raw, Object event) {
        try {
            raw.sendMessage(new TextMessage(json.writeValueAsString(event)));
        } catch (IOException ignored) {
            // the socket is being closed anyway
        }
    }
}
