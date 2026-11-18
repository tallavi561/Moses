package com.moses.monitor.ai.llm;

import java.util.List;
import java.util.Map;

/**
 * The only thing the rest of ai-service knows about a language model. Provider specifics (SDKs,
 * wire formats, auth) stay inside the implementations, so supporting another provider means one
 * more small adapter.
 */
public interface LlmClient {

    /** A tool the model may call: name, what it does, and a JSON Schema for its input. */
    record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) { }

    /** Runs a tool the model asked for and returns its result as text (thrown errors are shown to the model). */
    @FunctionalInterface
    interface ToolExecutor {
        String run(String toolName, Map<String, Object> input) throws Exception;
    }

    /** The final answer of an agent run, with what it cost. */
    record AgentResult(String text, int steps, long inputTokens, long outputTokens, String stopReason) { }

    /** Returns the model's text reply; throws on any transport/provider error. */
    String complete(String system, String user);

    /**
     * Lets the model call {@code tools} until it answers, at most {@code maxSteps} model calls.
     * Providers without tool-use support in this codebase yet throw {@link UnsupportedOperationException}.
     */
    default AgentResult runAgent(String system, String userMessage, List<ToolSpec> tools, ToolExecutor executor,
                                 int maxSteps) {
        throw new UnsupportedOperationException("tool use is not implemented for " + describe() + " yet");
    }

    /**
     * Checks that the key is accepted and the model exists, without generating anything (free).
     * Throws {@link IllegalArgumentException} with a message fit for the person who entered them.
     */
    default void verify() {
    }

    /** False for the placeholder used when no provider is configured. */
    default boolean isEnabled() {
        return true;
    }

    /** Short, secret-free label for logs/diagnostics, e.g. "anthropic:claude-opus-5-5". */
    String describe();
}
