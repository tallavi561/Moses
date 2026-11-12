package com.moses.monitor.ai.llm;

/**
 * The only thing the rest of ai-service knows about a language model: send a system prompt and a
 * user message, get the model's text back. Provider specifics (SDKs, wire formats, auth) stay
 * inside the implementations, so supporting another provider means one more small adapter.
 */
public interface LlmClient {

    /** Returns the model's text reply; throws on any transport/provider error. */
    String complete(String system, String user);

    /** False for the placeholder used when no provider is configured. */
    default boolean isEnabled() {
        return true;
    }

    /** Short, secret-free label for logs/diagnostics, e.g. "anthropic:claude-opus-5-5". */
    String describe();
}
