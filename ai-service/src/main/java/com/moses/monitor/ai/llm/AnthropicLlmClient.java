package com.moses.monitor.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.time.Duration;
import java.util.stream.Collectors;

/** Claude through the official Anthropic SDK. */
public class AnthropicLlmClient implements LlmClient {

    private static final long MAX_OUTPUT_TOKENS = 1024;

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmClient(String apiKey, String baseUrl, String model, Duration timeout) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(1);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.model = model;
    }

    @Override
    public String complete(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .system(system)
                .addUserMessage(user)
                .build();
        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }

    @Override
    public String describe() {
        return "anthropic:" + model;
    }
}
