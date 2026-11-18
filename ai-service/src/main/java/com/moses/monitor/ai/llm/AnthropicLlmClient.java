package com.moses.monitor.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Claude through the official Anthropic SDK. */
public class AnthropicLlmClient implements LlmClient {

    private static final long MAX_OUTPUT_TOKENS = 1024;
    private static final long AGENT_MAX_OUTPUT_TOKENS = 16_000;
    /** Longest tool result handed back to the model in one piece. */
    private static final int MAX_TOOL_RESULT_CHARS = 60_000;

    private final AnthropicClient client;
    private final AnthropicClient agentClient;
    private final String model;

    public AnthropicLlmClient(String apiKey, String baseUrl, String model, Duration timeout) {
        this(apiKey, baseUrl, model, timeout, Duration.ofSeconds(180));
    }

    public AnthropicLlmClient(String apiKey, String baseUrl, String model, Duration timeout, Duration agentTimeout) {
        this.client = build(apiKey, baseUrl, timeout);
        // Agent turns read code and logs and may think for a while: a much longer per-call limit.
        this.agentClient = build(apiKey, baseUrl, agentTimeout);
        this.model = model;
    }

    private static AnthropicClient build(String apiKey, String baseUrl, Duration timeout) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(1);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
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
        return textOf(response);
    }

    /**
     * The manual tool loop: call the model, run every tool it asked for, send all results back in
     * one user message, repeat until it stops asking. The model's own reply is appended to the
     * history as-is on every step, so its thinking blocks travel back unchanged.
     */
    @Override
    public AgentResult runAgent(String system, String userMessage, List<ToolSpec> tools, ToolExecutor executor,
                                int maxSteps) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(AGENT_MAX_OUTPUT_TOKENS)
                .system(system)
                // Chat questions rarely need the deepest reasoning; medium keeps answers quick and cheap.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                // Caches the growing prefix (tools, system prompt, earlier steps) across the loop.
                .cacheControl(CacheControlEphemeral.builder().build())
                // If a safety classifier declines a request, the API retries it on a fallback model
                // instead of returning an empty refusal.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(userMessage);
        for (ToolSpec spec : tools) {
            params.addTool(toTool(spec));
        }

        long inputTokens = 0;
        long outputTokens = 0;
        for (int step = 1; step <= maxSteps; step++) {
            Message response = agentClient.messages().create(params.build());
            inputTokens += response.usage().inputTokens()
                    + response.usage().cacheReadInputTokens().orElse(0L)
                    + response.usage().cacheCreationInputTokens().orElse(0L);
            outputTokens += response.usage().outputTokens();
            String stop = response.stopReason().map(StopReason::toString).orElse("unknown");

            List<ToolUseBlock> calls = response.content().stream()
                    .flatMap(block -> block.toolUse().stream())
                    .toList();
            boolean wantsTools = response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false);
            if (!wantsTools || calls.isEmpty()) {
                if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                    return new AgentResult("I can't help with that request.", step, inputTokens, outputTokens, stop);
                }
                return new AgentResult(textOf(response), step, inputTokens, outputTokens, stop);
            }

            params.addMessage(response);
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : calls) {
                results.add(ContentBlockParam.ofToolResult(runTool(call, executor)));
            }
            params.addUserMessageOfContentBlockParams(results);
        }
        return new AgentResult("I stopped after " + maxSteps
                + " steps without reaching an answer. Try a narrower question.", maxSteps, inputTokens, outputTokens,
                "max_steps");
    }

    private static ToolResultBlockParam runTool(ToolUseBlock call, ToolExecutor executor) {
        String output;
        boolean failed = false;
        try {
            Map<String, Object> input = inputOf(call);
            output = executor.run(call.name(), input);
        } catch (Exception e) {
            failed = true;
            output = "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (output == null) {
            output = "";
        }
        if (output.length() > MAX_TOOL_RESULT_CHARS) {
            output = output.substring(0, MAX_TOOL_RESULT_CHARS) + "\n...(truncated)";
        }
        return ToolResultBlockParam.builder()
                .toolUseId(call.id())
                .content(output)
                .isError(failed)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputOf(ToolUseBlock call) {
        Object converted = call._input().convert(Map.class);
        return converted == null ? Map.of() : new LinkedHashMap<>((Map<String, Object>) converted);
    }

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(spec.required())
                        .build())
                .build();
    }

    private static String textOf(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }

    @Override
    public void verify() {
        try {
            client.models().retrieve(model);
        } catch (com.anthropic.errors.AnthropicServiceException e) {
            int status = e.statusCode();
            if (status == 401) {
                throw new IllegalArgumentException("Anthropic rejected this API key.");
            }
            if (status == 403) {
                throw new IllegalArgumentException("This API key is not allowed to use the API.");
            }
            if (status == 404) {
                throw new IllegalArgumentException("Anthropic has no model named '" + model + "'.");
            }
            throw new IllegalStateException("Anthropic answered " + status);
        }
    }

    @Override
    public String describe() {
        return "anthropic:" + model;
    }
}
