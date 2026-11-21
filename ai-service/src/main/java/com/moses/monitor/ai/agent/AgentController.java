package com.moses.monitor.ai.agent;

import com.moses.monitor.ai.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat agent: answers one chat message, reading the project's repository and monitoring
 * data through tools. Called by chat-service only - the path is under /internal, which
 * api-gateway never proxies, and the caller must present INTERNAL_API_TOKEN, so nobody else can
 * call it and pick its own repository or project.
 */
@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    public record TranscriptLine(String author, boolean agent, String at, String text) { }

    public record ReplyTo(String authorName, String authorType, String text) { }

    public record AgentRequest(String projectName, String repoUrl, String channel, String askerName,
                               String question, ReplyTo replyTo, List<TranscriptLine> transcript) { }

    public record AgentReply(String text, int steps) { }

    private final LlmClient llm;
    private final AgentTools tools;
    private final int maxSteps;
    private final AgentQuota quota;
    private final byte[] internalToken;

    public AgentController(LlmClient llm, AgentTools tools, AgentQuota quota,
                           @Value("${moses.agent.max-steps:15}") int maxSteps,
                           @Value("${moses.internal.token:}") String internalToken) {
        this.internalToken = internalToken == null || internalToken.isBlank() ? null
                : internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.llm = llm;
        this.tools = tools;
        this.maxSteps = maxSteps;
        this.quota = quota;
    }

    @PostMapping("/internal/agent/reply")
    public AgentReply reply(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                            @RequestHeader("X-Moses-Project-Id") String projectId,
                            @RequestHeader("X-Moses-Environment") String environment,
                            @RequestBody AgentRequest request) {
        if (internalToken == null || token == null
                || !java.security.MessageDigest.isEqual(internalToken, token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal only");
        }
        if (!llm.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No AI model is set up for this project yet. The project owner can add one under Settings > AI model.");
        }
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty question");
        }
        quota.count(projectId);

        String repoUrl = RepoReader.isAcceptedUrl(request.repoUrl()) ? request.repoUrl().trim() : null;
        MonitoringReader.Scope scope = new MonitoringReader.Scope(projectId, environment);
        AgentTools.Session session = tools.session(scope, repoUrl);

        long started = System.currentTimeMillis();
        LlmClient.AgentResult result;
        try {
            result = llm.runAgent(AgentPrompt.system(request, environment, session.repoWebUrl(), session.branch()),
                    AgentPrompt.userMessage(request), session.specs(), session, maxSteps);
        } catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "The configured language model (" + llm.describe() + ") can't use tools yet - the agent needs Anthropic for now.");
        }
        log.info("agent answered project={} steps={} stop={} inputTokens={} outputTokens={} in {} ms",
                projectId, result.steps(), result.stopReason(), result.inputTokens(), result.outputTokens(),
                System.currentTimeMillis() - started);
        return new AgentReply(result.text(), result.steps());
    }

    static List<TranscriptLine> safe(List<TranscriptLine> lines) {
        return lines == null ? List.of() : new ArrayList<>(lines);
    }
}
