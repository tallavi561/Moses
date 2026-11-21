package com.moses.monitor.process;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Request flows of one environment: the ones MoSes learned and suggests, and the ones the team
 * approved or added (checked by {@link IncidentDetector}). Changes are editors-only (api-gateway).
 */
@RestController
@RequestMapping("/api/process/flows")
public class TraceFlowController {

    public record NewFlow(String entryService, String routeExample, String targetService, Integer maxSeconds,
                          Double maxBrokenPercent, Integer minTraces) { }

    public record FlowUpdate(TraceFlowStatus status, Integer maxSeconds, Double maxBrokenPercent, Integer minTraces) { }

    private final MongoTemplate mongoTemplate;
    private final FlowLearner learner;

    public TraceFlowController(MongoTemplate mongoTemplate, FlowLearner learner) {
        this.mongoTemplate = mongoTemplate;
        this.learner = learner;
    }

    /** Suggestions first (most traces first), then active, paused, rejected. */
    @GetMapping
    public List<TraceFlow> list(@RequestHeader(Scope.PROJECT) String projectId,
                                @RequestHeader(Scope.ENVIRONMENT) String environment) {
        return mongoTemplate.find(scope(projectId, environment), TraceFlow.class).stream()
                .sorted(Comparator.comparing(TraceFlow::getStatus)
                        .thenComparing(f -> f.getLearnedSamples() == null ? 0 : -f.getLearnedSamples()))
                .toList();
    }

    /** A flow added by hand is active at once. */
    @PostMapping
    public ResponseEntity<TraceFlow> create(@RequestHeader(Scope.PROJECT) String projectId,
                                            @RequestHeader(Scope.ENVIRONMENT) String environment,
                                            @RequestHeader(value = Scope.USER, required = false) String userId,
                                            @RequestBody NewFlow body) {
        String entry = required(body.entryService(), "entryService");
        String target = required(body.targetService(), "targetService");
        if (entry.equals(target)) {
            throw bad("the entry and target services must differ");
        }
        String routeExample = body.routeExample() == null || body.routeExample().isBlank() ? null : body.routeExample().strip();
        String route = routeExample == null ? null : ErrorSignature.of(routeExample);
        TraceFlow flow = mongoTemplate.findOne(new Query(Criteria.where("project_id").is(projectId)
                .and("environment").is(environment).and("entry_service").is(entry).and("route").is(route)
                .and("target_service").is(target)), TraceFlow.class);
        boolean created = flow == null;
        if (created) {
            flow = new TraceFlow();
            flow.setProjectId(projectId);
            flow.setEnvironment(environment);
            flow.setEntryService(entry);
            flow.setRoute(route);
            flow.setRouteExample(routeExample);
            flow.setTargetService(target);
            flow.setCreatedBy(userId);
            flow.setCreatedAt(Instant.now());
            flow.setMaxSeconds(30);
        }
        apply(flow, body.maxSeconds(), body.maxBrokenPercent(), body.minTraces());
        flow.setStatus(TraceFlowStatus.ACTIVE);
        flow.setDecidedBy(userId);
        flow.setDecidedAt(Instant.now());
        mongoTemplate.save(flow);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(flow);
    }

    /** Approve (ACTIVE), pause, reject - and tune the deadline and thresholds. */
    @PatchMapping("/{id}")
    public TraceFlow update(@RequestHeader(Scope.PROJECT) String projectId,
                            @RequestHeader(Scope.ENVIRONMENT) String environment,
                            @RequestHeader(value = Scope.USER, required = false) String userId,
                            @PathVariable String id,
                            @RequestBody FlowUpdate body) {
        TraceFlow flow = find(projectId, environment, id);
        apply(flow, body.maxSeconds(), body.maxBrokenPercent(), body.minTraces());
        if (body.status() != null && body.status() != flow.getStatus()) {
            if (body.status() == TraceFlowStatus.SUGGESTED) {
                throw bad("a flow can't be turned back into a suggestion");
            }
            flow.setStatus(body.status());
            flow.setDecidedBy(userId);
            flow.setDecidedAt(Instant.now());
        }
        mongoTemplate.save(flow);
        return flow;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(Scope.PROJECT) String projectId,
                                       @RequestHeader(Scope.ENVIRONMENT) String environment,
                                       @PathVariable String id) {
        mongoTemplate.remove(find(projectId, environment, id));
        return ResponseEntity.noContent().build();
    }

    /** Looks for new suggestions in this environment now instead of waiting for the next run. */
    @PostMapping("/learn")
    public Map<String, Object> learn(@RequestHeader(Scope.PROJECT) String projectId,
                                     @RequestHeader(Scope.ENVIRONMENT) String environment) {
        int added = learner.learn(projectId, environment, Instant.now());
        return Map.of("added", added, "minSamples", learner.minSamples(), "flows", list(projectId, environment));
    }

    private static void apply(TraceFlow flow, Integer maxSeconds, Double maxBrokenPercent, Integer minTraces) {
        if (maxSeconds != null) {
            if (maxSeconds < 1 || maxSeconds > 3600) throw bad("maxSeconds must be 1-3600");
            flow.setMaxSeconds(maxSeconds);
        }
        if (maxBrokenPercent != null) {
            if (maxBrokenPercent.isNaN() || maxBrokenPercent < 1 || maxBrokenPercent > 100) throw bad("maxBrokenPercent must be 1-100");
            flow.setMaxBrokenPercent(maxBrokenPercent);
        }
        if (minTraces != null) {
            if (minTraces < 1 || minTraces > 10_000) throw bad("minTraces must be 1-10000");
            flow.setMinTraces(minTraces);
        }
    }

    private TraceFlow find(String projectId, String environment, String id) {
        TraceFlow flow = mongoTemplate.findOne(new Query(Criteria.where("_id").is(id)
                .and("project_id").is(projectId).and("environment").is(environment)), TraceFlow.class);
        if (flow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "flow not found");
        }
        return flow;
    }

    private static Query scope(String projectId, String environment) {
        return new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw bad(field + " is required");
        return value.strip();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
