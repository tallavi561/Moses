package com.moses.monitor.ai.llm;

/** Placeholder when no provider/key is configured: callers fall back to the rule-based translator. */
public class DisabledLlmClient implements LlmClient {

    @Override
    public String complete(String system, String user) {
        throw new IllegalStateException("No LLM configured");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String describe() {
        return "none";
    }
}
