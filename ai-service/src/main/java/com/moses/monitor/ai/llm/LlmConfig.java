package com.moses.monitor.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

/**
 * Chooses the LLM provider from configuration (env vars LLM_PROVIDER / LLM_MODEL / LLM_API_KEY /
 * LLM_BASE_URL). Anything missing or unknown yields a {@link DisabledLlmClient}, so the service
 * keeps working (rule-based translation) without a key.
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(@Value("${moses.llm.provider:none}") String provider,
                               @Value("${moses.llm.model:}") String model,
                               @Value("${moses.llm.api-key:}") String apiKey,
                               @Value("${moses.llm.base-url:}") String baseUrl,
                               @Value("${moses.llm.timeout-seconds:20}") int timeoutSeconds) {
        String kind = provider == null ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

        LlmClient client = switch (kind) {
            case "anthropic" -> {
                if (apiKey.isBlank() || model.isBlank()) {
                    log.warn("LLM_PROVIDER=anthropic but LLM_API_KEY or LLM_MODEL is missing; LLM disabled");
                    yield new DisabledLlmClient();
                }
                yield new AnthropicLlmClient(apiKey, baseUrl, model, timeout);
            }
            case "openai-compatible", "openai" -> {
                if (model.isBlank() || baseUrl.isBlank()) {
                    log.warn("LLM_PROVIDER={} needs LLM_MODEL and LLM_BASE_URL; LLM disabled", kind);
                    yield new DisabledLlmClient();
                }
                yield new OpenAiCompatibleLlmClient(apiKey, baseUrl, model, timeout);
            }
            case "none", "" -> new DisabledLlmClient();
            default -> {
                log.warn("Unknown LLM_PROVIDER '{}'; LLM disabled (use anthropic | openai-compatible | none)", kind);
                yield new DisabledLlmClient();
            }
        };
        log.info("LLM provider: {}", client.describe());
        return client;
    }
}
