package com.moses.monitor.ai.incident;

import com.moses.monitor.ai.agent.AgentQuota;
import com.moses.monitor.ai.agent.MonitoringReader;
import com.moses.monitor.ai.llm.LlmClient;
import com.moses.monitor.ai.llm.ProjectLlmResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Investigate with AI" on one incident. Starts the investigation and returns at once (202): an
 * agent run can take longer than a browser request should wait. The incident shows RUNNING until
 * {@link IncidentInvestigator} stores the result; the page polls it. Editors only (api-gateway),
 * paid with the project's own key, and counted in the same daily cap as chat answers.
 */
@RestController
@RequestMapping("/api/ai/incidents")
public class IncidentInvestigationController {

    /** A RUNNING investigation older than this is assumed lost (e.g. a restart) and can be started again. */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    public record InvestigateRequest(String language) { }

    private final ProjectLlmResolver resolver;
    private final MonitoringReader monitoring;
    private final IncidentInvestigator investigator;
    private final AgentQuota quota;

    public IncidentInvestigationController(ProjectLlmResolver resolver, MonitoringReader monitoring,
                                           IncidentInvestigator investigator, AgentQuota quota) {
        this.resolver = resolver;
        this.monitoring = monitoring;
        this.investigator = investigator;
        this.quota = quota;
    }

    @PostMapping("/{id}/investigate")
    public ResponseEntity<Map<String, Object>> investigate(@RequestHeader("X-Moses-Project-Id") String projectId,
                                                           @RequestHeader("X-Moses-Environment") String environment,
                                                           @RequestHeader(value = "X-Moses-User-Id", required = false) String userId,
                                                           @PathVariable String id,
                                                           @RequestBody(required = false) InvestigateRequest body) {
        // Resolved here, on the request thread: the background run has no request to read the scope from.
        LlmClient llm = resolver.forScope(projectId, environment);
        if (!llm.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No AI model is set up for this project - add one in Settings > AI model");
        }
        MonitoringReader.Scope scope = new MonitoringReader.Scope(projectId, environment);
        Map<String, Object> incident;
        try {
            incident = monitoring.incident(scope, id);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found");
        }
        if (incident.get("aiInvestigation") instanceof Map<?, ?> current && "RUNNING".equals(current.get("status"))
                && current.get("startedAt") != null
                && Instant.parse(String.valueOf(current.get("startedAt"))).isAfter(Instant.now().minus(STALE_AFTER))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An investigation of this incident is already running");
        }
        quota.count(projectId);

        Instant startedAt = Instant.now();
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("status", "RUNNING");
        running.put("model", llm.describe());
        running.put("requestedBy", userId);
        running.put("startedAt", startedAt.toString());
        monitoring.saveInvestigation(scope, userId, id, running);

        Map<String, String> project = resolver.projectInfo(projectId);
        String language = body != null && "he".equals(body.language()) ? "he" : "en";
        Thread.ofVirtual().name("investigate-" + id).start(() -> investigator.run(llm, scope, userId, incident,
                project.get("name"), project.get("repoUrl"), language, startedAt));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(running);
    }
}
