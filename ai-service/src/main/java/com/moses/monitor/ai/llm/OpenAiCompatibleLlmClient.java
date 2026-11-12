package com.moses.monitor.ai.llm;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Any provider that speaks the OpenAI chat-completions format: OpenAI, OpenRouter, Groq,
 * Together, Gemini's compatibility endpoint, and local servers (Ollama, vLLM, LM Studio).
 * {@code baseUrl} is the API root up to (not including) {@code /chat/completions},
 * e.g. {@code https://api.openai.com/v1}.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestTemplate http;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleLlmClient(String apiKey, String baseUrl, String model, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 10_000));
        factory.setReadTimeout((int) timeout.toMillis());
        this.http = new RestTemplate(factory);
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = root + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String system, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        Map<String, Object> response = http.postForObject(endpoint, new HttpEntity<>(body, headers), Map.class);
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("LLM response had no choices");
        }
        Object message = ((Map<String, Object>) choices.get(0)).get("message");
        Object content = message instanceof Map<?, ?> m ? m.get("content") : null;
        if (!(content instanceof String text)) {
            throw new IllegalStateException("LLM response had no message content");
        }
        return text;
    }

    @Override
    public String describe() {
        return "openai-compatible:" + model;
    }
}
