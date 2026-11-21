package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Saved dashboards for one environment. A project member manages them from the UI; a monitored
 * service's own integration agent can also request one for itself with nothing but its API key
 * (see moses-monitoring-skill.md) - that's the one write an API key is allowed here for (see
 * AuthInterceptor.acceptsApiKey in api-gateway).
 *
 * A dashboard is SHARED (everyone in the environment) or PRIVATE (only the user who created it;
 * to anyone else it doesn't exist - 404). Only its creator can switch between the two.
 *
 * An integration agent's request (API key, no user) is idempotent by (scope, serviceName) among
 * the agent's own dashboards: re-running the skill updates the title instead of adding a
 * duplicate. A person creating one always gets a new dashboard.
 */
@RestController
@RequestMapping("/api/process/dashboards")
public class DashboardController {

    private final MongoTemplate mongoTemplate;

    public DashboardController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public List<DashboardView> list(@RequestHeader(Scope.PROJECT) String projectId,
                                    @RequestHeader(Scope.ENVIRONMENT) String environment,
                                    @RequestHeader(value = Scope.USER, required = false) String userId) {
        Query query = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment))
                .with(Sort.by(Sort.Direction.ASC, "created_at"));
        return mongoTemplate.find(query, Dashboard.class).stream()
                .filter(d -> d.visibleTo(userId))
                .map(d -> DashboardView.of(d, userId))
                .toList();
    }

    @PostMapping
    public ResponseEntity<DashboardView> create(@RequestHeader(Scope.PROJECT) String projectId,
                                                @RequestHeader(Scope.ENVIRONMENT) String environment,
                                                @RequestHeader(value = Scope.USER, required = false) String userId,
                                                @Valid @RequestBody DashboardRequest request) {
        if (request.getScope() == DashboardScope.SERVICE
                && (request.getServiceName() == null || request.getServiceName().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceName is required for a SERVICE dashboard");
        }
        String serviceName = request.getScope() == DashboardScope.SERVICE ? request.getServiceName().trim() : null;

        DashboardVisibility visibility = request.getVisibility() == null ? DashboardVisibility.SHARED : request.getVisibility();
        if (visibility == DashboardVisibility.PRIVATE && userId == null) {
            throw badRequest("only a signed-in user can create a private dashboard");
        }

        Dashboard existing = null;
        if (userId == null) {
            Criteria match = Criteria.where("project_id").is(projectId).and("environment").is(environment)
                    .and("scope").is(request.getScope()).and("service_name").is(serviceName)
                    .and("owner_user_id").is(null);
            existing = mongoTemplate.findOne(new Query(match), Dashboard.class);
        }

        String title = request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle().trim()
                : defaultTitle(request.getScope(), serviceName, environment);

        Dashboard dashboard = existing != null ? existing : new Dashboard();
        if (existing == null) {
            dashboard.setProjectId(projectId);
            dashboard.setEnvironment(environment);
            dashboard.setScope(request.getScope());
            dashboard.setServiceName(serviceName);
            dashboard.setCreatedBy(userId == null ? "agent" : "user");
            dashboard.setOwnerUserId(userId);
            dashboard.setVisibility(visibility);
            dashboard.setCreatedAt(Instant.now());
        }
        dashboard.setTitle(title);
        mongoTemplate.save(dashboard);

        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(DashboardView.of(dashboard, userId));
    }

    @GetMapping("/{id}")
    public DashboardView get(@RequestHeader(Scope.PROJECT) String projectId,
                             @RequestHeader(Scope.ENVIRONMENT) String environment,
                             @RequestHeader(value = Scope.USER, required = false) String userId,
                             @PathVariable String id) {
        return DashboardView.of(find(projectId, environment, id, userId), userId);
    }

    /** Changes only the fields that are set; anything out of range is a 400, not silently fixed. */
    @PatchMapping("/{id}")
    public DashboardView update(@RequestHeader(Scope.PROJECT) String projectId,
                                @RequestHeader(Scope.ENVIRONMENT) String environment,
                                @RequestHeader(value = Scope.USER, required = false) String userId,
                                @PathVariable String id,
                                @RequestBody DashboardUpdate update) {
        Dashboard dashboard = find(projectId, environment, id, userId);
        if (update.visibility() != null && update.visibility() != dashboard.getVisibility()) {
            if (userId == null || !userId.equals(dashboard.getOwnerUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the dashboard's creator can change who sees it");
            }
            dashboard.setVisibility(update.visibility());
        }
        if (update.title() != null) {
            String title = update.title().trim();
            if (title.isEmpty() || title.length() > DashboardUpdate.MAX_TITLE) {
                throw badRequest("title must be 1-" + DashboardUpdate.MAX_TITLE + " characters");
            }
            dashboard.setTitle(title);
        }
        if (update.services() != null) {
            if (update.services().size() > DashboardUpdate.MAX_SERVICES) {
                throw badRequest("at most " + DashboardUpdate.MAX_SERVICES + " services");
            }
            List<String> services = new ArrayList<>(new LinkedHashSet<>(update.services().stream()
                    .filter(s -> s != null && !s.isBlank()).map(String::trim).toList()));
            dashboard.setServices(services);
        }
        if (update.metric() != null) {
            if (!DashboardUpdate.METRICS.contains(update.metric())) {
                throw badRequest("metric must be one of " + DashboardUpdate.METRICS);
            }
            dashboard.setMetric(update.metric());
        }
        if (update.minutesBack() != null) {
            if (!DashboardUpdate.RANGES.contains(update.minutesBack())) {
                throw badRequest("minutesBack must be one of " + DashboardUpdate.RANGES);
            }
            dashboard.setMinutesBack(update.minutesBack());
        }
        mongoTemplate.save(dashboard);
        return DashboardView.of(dashboard, userId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(Scope.PROJECT) String projectId,
                                       @RequestHeader(Scope.ENVIRONMENT) String environment,
                                       @RequestHeader(value = Scope.USER, required = false) String userId,
                                       @PathVariable String id) {
        mongoTemplate.remove(find(projectId, environment, id, userId));
        return ResponseEntity.noContent().build();
    }

    /** Someone else's private dashboard is "not found", same as one that doesn't exist. */
    private Dashboard find(String projectId, String environment, String id, String userId) {
        Dashboard dashboard = mongoTemplate.findOne(new Query(Criteria.where("_id").is(id)
                .and("project_id").is(projectId).and("environment").is(environment)), Dashboard.class);
        if (dashboard == null || !dashboard.visibleTo(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dashboard not found");
        }
        return dashboard;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String defaultTitle(DashboardScope scope, String serviceName, String environment) {
        return scope == DashboardScope.SERVICE ? serviceName : environment + " overview";
    }
}
