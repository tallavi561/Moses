package com.moses.monitor.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;

/**
 * Builds the client for one provider/model/key. The settings come from the project (or one of its
 * environments) - owners enter them in MoSes Settings, see {@link ProjectLlmResolver}. Anything
 * missing or unknown yields a {@link DisabledLlmClient}, so the service keeps working
 * (rule-based search translation) without a key.
 */
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    public LlmClient llmClient(String provider,
                               String model,
                               String apiKey,
                               String baseUrl,
                               int timeoutSeconds,
                               int agentTimeoutSeconds) {
        String kind = provider == null ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

        LlmClient client = switch (kind) {
            case "anthropic" -> {
                if (apiKey.isBlank() || model.isBlank()) {
                    log.warn("LLM_PROVIDER=anthropic but LLM_API_KEY or LLM_MODEL is missing; LLM disabled");
                    yield new DisabledLlmClient();
                }
                yield new AnthropicLlmClient(apiKey, baseUrl, model, timeout,
                        Duration.ofSeconds(Math.max(30, agentTimeoutSeconds)));
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
