package com.moses.monitor.ai.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tool loop against a fake Messages API: the model asks for a tool, gets the result, answers. */
class AnthropicAgentLoopTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = responses.get(Math.min(requests.size() - 1, responses.size() - 1));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String message(String content, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":" + content
                + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    @Test
    void runsTheRequestedToolAndReturnsTheFinalText() {
        responses.add(message("[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"repo_search\",\"input\":{\"query\":\"JwtService\"}}]",
                "tool_use"));
        responses.add(message("[{\"type\":\"text\",\"text\":\"It is in auth-service.\"}]", "end_turn"));

        AnthropicLlmClient client = new AnthropicLlmClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-opus-5-5", Duration.ofSeconds(5), Duration.ofSeconds(5));
        List<String> calls = new ArrayList<>();
        LlmClient.AgentResult result = client.runAgent("system", "where is JwtService?",
                List.of(new LlmClient.ToolSpec("repo_search", "search", Map.of("query", Map.of("type", "string")), List.of("query"))),
                (name, input) -> {
                    calls.add(name + ":" + input.get("query"));
                    return "auth-service/src/main/java/JwtService.java:1:class JwtService";
                }, 5);

        assertEquals("It is in auth-service.", result.text());
        assertEquals(2, result.steps());
        assertEquals(List.of("repo_search:JwtService"), calls);
        assertEquals(2, requests.size());
        String second = requests.get(1);
        assertTrue(second.contains("\"tool_result\""), second);
        assertTrue(second.contains("tu_1"), second);
        assertTrue(second.contains("class JwtService"), second);
        assertTrue(requests.get(0).contains("\"fallbacks\":\"default\""), requests.get(0));
    }

    @Test
    void aFailingToolIsReportedToTheModelAsAnError() {
        responses.add(message("[{\"type\":\"tool_use\",\"id\":\"tu_9\",\"name\":\"get_trace\",\"input\":{}}]", "tool_use"));
        responses.add(message("[{\"type\":\"text\",\"text\":\"I need a trace id.\"}]", "end_turn"));

        AnthropicLlmClient client = new AnthropicLlmClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-opus-5-5", Duration.ofSeconds(5), Duration.ofSeconds(5));
        LlmClient.AgentResult result = client.runAgent("s", "q",
                List.of(new LlmClient.ToolSpec("get_trace", "t", Map.of(), List.of())),
                (name, input) -> {
                    throw new IllegalArgumentException("trace_id is required");
                }, 5);

        assertEquals("I need a trace id.", result.text());
        assertTrue(requests.get(1).contains("\"is_error\":true"), requests.get(1));
        assertTrue(requests.get(1).contains("trace_id is required"), requests.get(1));
    }

    @Test
    void stopsAfterTheStepLimit() {
        responses.add(message("[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"get_services\",\"input\":{}}]", "tool_use"));

        AnthropicLlmClient client = new AnthropicLlmClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-opus-5-5", Duration.ofSeconds(5), Duration.ofSeconds(5));
        LlmClient.AgentResult result = client.runAgent("s", "q",
                List.of(new LlmClient.ToolSpec("get_services", "s", Map.of(), List.of())), (n, i) -> "ok", 3);

        assertEquals("max_steps", result.stopReason());
        assertEquals(3, requests.size());
    }
}
