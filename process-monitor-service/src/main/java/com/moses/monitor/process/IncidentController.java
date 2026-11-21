package com.moses.monitor.process;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Incidents page: incidents of one environment (opened and closed by {@link IncidentDetector}),
 * and the per-service {@link AlertRule}s that decide when errors become one.
 */
@RestController
@RequestMapping("/api/process")
public class IncidentController {

    private static final int MAX_LIMIT = 200;

    /** A service's rule as the page shows it: {@code custom} is false while it uses the defaults. */
    public record RuleView(String serviceName, int minErrors, double factor, boolean muted, boolean custom,
                           int windowMinutes) { }

    public record RuleUpdate(Integer minErrors, Double factor, Boolean muted) { }

    /** What the team concluded. Fields left out stay as they are; an empty string clears a text. */
    public record ReviewUpdate(IncidentReview status, String rootCause, String resolution) { }

    static final int MAX_REVIEW_TEXT = 4000;

    private final MongoTemplate mongoTemplate;
    private final IncidentLibrary library;
    private final int windowMinutes;

    public IncidentController(MongoTemplate mongoTemplate, IncidentLibrary library,
                              @org.springframework.beans.factory.annotation.Value("${moses.incidents.window-minutes:5}") int windowMinutes) {
        this.mongoTemplate = mongoTemplate;
        this.library = library;
        this.windowMinutes = Math.max(1, windowMinutes);
    }

    @GetMapping("/incidents")
    public List<Incident> list(@RequestHeader(Scope.PROJECT) String projectId,
                               @RequestHeader(Scope.ENVIRONMENT) String environment,
                               @RequestParam(required = false) IncidentState state,
                               @RequestParam(required = false) String service,
                               @RequestParam(defaultValue = "100") int limit) {
        Criteria criteria = Criteria.where("project_id").is(projectId).and("environment").is(environment);
        if (state != null) {
            criteria = criteria.and("state").is(state);
        }
        if (service != null && !service.isBlank()) {
            criteria = criteria.and("service_name").is(service.trim());
        }
        return mongoTemplate.find(new Query(criteria).with(Sort.by(Sort.Direction.DESC, "opened_at"))
                .limit(Math.max(1, Math.min(limit, MAX_LIMIT))), Incident.class);
    }

    /**
     * The incident library: past incidents with what the team wrote about them. By default across
     * all environments of the project (a fix found in staging helps in prod too).
     */
    @GetMapping("/incidents/library")
    public List<Incident> library(@RequestHeader(Scope.PROJECT) String projectId,
                                  @RequestHeader(Scope.ENVIRONMENT) String environment,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) IncidentReview status,
                                  @RequestParam(required = false) String service,
                                  @RequestParam(defaultValue = "true") boolean allEnvironments,
                                  @RequestParam(defaultValue = "100") int limit) {
        return library.search(projectId, allEnvironments ? null : environment, q, status, service, limit);
    }

    @GetMapping("/incidents/{id}")
    public Incident get(@RequestHeader(Scope.PROJECT) String projectId,
                        @RequestHeader(Scope.ENVIRONMENT) String environment,
                        @PathVariable String id) {
        return find(projectId, environment, id);
    }

    /** Earlier incidents like this one (same kind of error, or the same service going down). */
    @GetMapping("/incidents/{id}/similar")
    public List<Incident> similar(@RequestHeader(Scope.PROJECT) String projectId,
                                  @RequestHeader(Scope.ENVIRONMENT) String environment,
                                  @PathVariable String id,
                                  @RequestParam(defaultValue = "5") int limit) {
        return library.similar(find(projectId, environment, id), Math.max(1, Math.min(limit, 50)));
    }

    /** Writes the team's conclusion: status, root cause, what fixed it. Editors only (gateway). */
    @PutMapping("/incidents/{id}/review")
    public Incident review(@RequestHeader(Scope.PROJECT) String projectId,
                           @RequestHeader(Scope.ENVIRONMENT) String environment,
                           @RequestHeader(value = Scope.USER, required = false) String userId,
                           @PathVariable String id,
                           @RequestBody ReviewUpdate update) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a signed-in user can review an incident");
        }
        Incident incident = find(projectId, environment, id);
        if (update.status() != null) {
            incident.setReviewStatus(update.status());
        }
        if (update.rootCause() != null) {
            incident.setRootCause(reviewText(update.rootCause(), "rootCause"));
        }
        if (update.resolution() != null) {
            incident.setResolution(reviewText(update.resolution(), "resolution"));
        }
        incident.setReviewedBy(userId);
        incident.setReviewedAt(java.time.Instant.now());
        mongoTemplate.save(incident);
        return incident;
    }

    static final int MAX_AI_TEXT = 8000;

    /**
     * Stores an AI investigation of the incident (written by ai-service, which forwards the user
     * who asked). Lengths are capped here so a runaway model reply can't bloat the document.
     */
    @PutMapping("/incidents/{id}/investigation")
    public Incident saveInvestigation(@RequestHeader(Scope.PROJECT) String projectId,
                                      @RequestHeader(Scope.ENVIRONMENT) String environment,
                                      @PathVariable String id,
                                      @RequestBody Incident.AiInvestigation body) {
        if (body.status() == null || !List.of("RUNNING", "DONE", "FAILED").contains(body.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be RUNNING, DONE or FAILED");
        }
        Incident incident = find(projectId, environment, id);
        List<String> evidence = body.evidence() == null ? List.of()
                : body.evidence().stream().filter(e -> e != null && !e.isBlank()).limit(10).map(e -> cap(e, 500)).toList();
        incident.setAiInvestigation(new Incident.AiInvestigation(body.status(), cap(body.summary(), MAX_AI_TEXT),
                cap(body.rootCause(), MAX_AI_TEXT), cap(body.suggestedFix(), MAX_AI_TEXT), cap(body.confidence(), 20),
                evidence, cap(body.error(), 1000), cap(body.model(), 200), body.requestedBy(), body.startedAt(),
                body.finishedAt(), body.steps()));
        mongoTemplate.save(incident);
        return incident;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    private static String reviewText(String text, String field) {
        String t = text.strip();
        if (t.length() > MAX_REVIEW_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be at most " + MAX_REVIEW_TEXT + " characters");
        }
        return t.isEmpty() ? null : t;
    }

    private Incident find(String projectId, String environment, String id) {
        Incident incident = mongoTemplate.findOne(new Query(Criteria.where("_id").is(id)
                .and("project_id").is(projectId).and("environment").is(environment)), Incident.class);
        if (incident == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found");
        }
        return incident;
    }

    /** Every known service of the environment with the rule in effect for it. */
    @GetMapping("/alert-rules")
    public List<RuleView> rules(@RequestHeader(Scope.PROJECT) String projectId,
                                @RequestHeader(Scope.ENVIRONMENT) String environment) {
        Map<String, AlertRule> custom = new HashMap<>();
        mongoTemplate.find(scopeQuery(projectId, environment), AlertRule.class).forEach(r -> custom.put(r.getServiceName(), r));
        TreeSet<String> names = new TreeSet<>(custom.keySet());
        mongoTemplate.find(scopeQuery(projectId, environment), ServiceDefinition.class).forEach(s -> names.add(s.getName()));
        return names.stream().map(name -> {
            AlertRule r = custom.get(name);
            return r == null
                    ? new RuleView(name, AlertRule.DEFAULT_MIN_ERRORS, AlertRule.DEFAULT_FACTOR, false, false, windowMinutes)
                    : new RuleView(name, r.getMinErrors(), r.getFactor(), r.isMuted(), true, windowMinutes);
        }).toList();
    }

    @PutMapping("/alert-rules")
    public RuleView saveRule(@RequestHeader(Scope.PROJECT) String projectId,
                             @RequestHeader(Scope.ENVIRONMENT) String environment,
                             @RequestParam String service,
                             @RequestBody RuleUpdate update) {
        String name = service.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "service is required");
        }
        AlertRule rule = mongoTemplate.findOne(new Query(Criteria.where("project_id").is(projectId)
                .and("environment").is(environment).and("service_name").is(name)), AlertRule.class);
        if (rule == null) {
            rule = AlertRule.defaults(name);
            rule.setProjectId(projectId);
            rule.setEnvironment(environment);
        }
        if (update.minErrors() != null) {
            if (update.minErrors() < 1 || update.minErrors() > 100_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minErrors must be 1-100000");
            }
            rule.setMinErrors(update.minErrors());
        }
        if (update.factor() != null) {
            if (update.factor() < 1 || update.factor() > 100 || update.factor().isNaN()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "factor must be 1-100");
            }
            rule.setFactor(update.factor());
        }
        if (update.muted() != null) {
            rule.setMuted(update.muted());
        }
        mongoTemplate.save(rule);
        return new RuleView(name, rule.getMinErrors(), rule.getFactor(), rule.isMuted(), true, windowMinutes);
    }

    /** Back to the defaults. */
    @DeleteMapping("/alert-rules")
    public ResponseEntity<Void> resetRule(@RequestHeader(Scope.PROJECT) String projectId,
                                          @RequestHeader(Scope.ENVIRONMENT) String environment,
                                          @RequestParam String service) {
        mongoTemplate.remove(new Query(Criteria.where("project_id").is(projectId)
                .and("environment").is(environment).and("service_name").is(service.trim())), AlertRule.class);
        return ResponseEntity.noContent().build();
    }

    private static Query scopeQuery(String projectId, String environment) {
        return new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment));
    }
}
