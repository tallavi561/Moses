package com.moses.monitor.ai.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talks to a throwaway local HTTP server, so no real provider or key is needed. */
class LlmClientAdaptersTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void openAiCompatibleSendsBearerKeyAndReadsTheFirstChoice() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"words\\\":[\\\"x\\\"]}\"}}]}";

        LlmClient client = new OpenAiCompatibleLlmClient("sk-test", baseUrl() + "/v1/", "some-model", Duration.ofSeconds(5));
        String reply = client.complete("be brief", "hello");

        assertEquals("{\"words\":[\"x\"]}", reply);
        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer sk-test", lastAuth.get());
        assertTrue(lastBody.get().contains("\"model\":\"some-model\""));
        assertTrue(lastBody.get().contains("be brief") && lastBody.get().contains("hello"));
    }

    @Test
    void openAiCompatibleSurfacesProviderErrors() {
        status = 401;
        responseBody = "{\"error\":\"bad key\"}";

        LlmClient client = new OpenAiCompatibleLlmClient("bad", baseUrl() + "/v1", "m", Duration.ofSeconds(5));

        assertThrows(RuntimeException.class, () -> client.complete("s", "u"));
    }

    @Test
    void openAiCompatibleRejectsAReplyWithoutChoices() {
        responseBody = "{\"choices\":[]}";

        LlmClient client = new OpenAiCompatibleLlmClient("k", baseUrl() + "/v1", "m", Duration.ofSeconds(5));

        assertThrows(IllegalStateException.class, () -> client.complete("s", "u"));
    }

    @Test
    void anthropicAdapterSendsTheKeyAndJoinsTextBlocks() {
        responseBody = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"text","text":"{\\"words\\":"},{"type":"text","text":"[\\"y\\"]}"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":5,"output_tokens":5}}""";

        LlmClient client = new AnthropicLlmClient("sk-ant-test", baseUrl(), "claude-test", Duration.ofSeconds(5));
        String reply = client.complete("be brief", "hello");

        assertEquals("{\"words\":[\"y\"]}", reply);
        assertEquals("/v1/messages", lastPath.get());
        // Anthropic SDK 3.x sends the key as a bearer credential (the SDK, not this code, owns the format).
        assertEquals("Bearer sk-ant-test", lastAuth.get());
        assertTrue(lastBody.get().contains("claude-test"));
        assertTrue(lastBody.get().contains("be brief") && lastBody.get().contains("hello"));
    }

    @Test
    void configPicksTheProviderAndFallsBackToDisabled() {
        LlmConfig config = new LlmConfig();

        assertEquals("none", config.llmClient("none", "", "", "", 5).describe());
        assertEquals("none", config.llmClient("anthropic", "claude-x", "", "", 5).describe()); // no key
        assertEquals("none", config.llmClient("openai-compatible", "m", "k", "", 5).describe()); // no base url
        assertEquals("none", config.llmClient("mystery", "m", "k", "u", 5).describe());
        assertEquals("anthropic:claude-x", config.llmClient("anthropic", "claude-x", "k", "", 5).describe());
        assertEquals("openai-compatible:gpt", config.llmClient("openai-compatible", "gpt", "k", "http://h/v1", 5).describe());
    }
}
