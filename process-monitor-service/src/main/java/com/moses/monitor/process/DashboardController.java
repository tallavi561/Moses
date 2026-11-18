package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * Saved dashboards for one environment. A project member manages them from the UI; a monitored
 * service's own integration agent can also request one for itself with nothing but its API key
 * (see moses-monitoring-skill.md) - that's the one write an API key is allowed here for (see
 * AuthInterceptor.acceptsApiKey in api-gateway).
 *
 * Creating is idempotent by (scope, serviceName): asking again (e.g. the skill re-run on every
 * integration) updates the existing dashboard's title instead of creating a duplicate.
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
                                    @RequestHeader(Scope.ENVIRONMENT) String environment) {
        Query query = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment))
                .with(Sort.by(Sort.Direction.ASC, "created_at"));
        return mongoTemplate.find(query, Dashboard.class).stream().map(DashboardView::of).toList();
    }

    @PostMapping
    public ResponseEntity<DashboardView> create(@RequestHeader(Scope.PROJECT) String projectId,
                                                @RequestHeader(Scope.ENVIRONMENT) String environment,
                                                @Valid @RequestBody DashboardRequest request) {
        if (request.getScope() == DashboardScope.SERVICE
                && (request.getServiceName() == null || request.getServiceName().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceName is required for a SERVICE dashboard");
        }
        String serviceName = request.getScope() == DashboardScope.SERVICE ? request.getServiceName().trim() : null;

        Criteria match = Criteria.where("project_id").is(projectId).and("environment").is(environment)
                .and("scope").is(request.getScope()).and("service_name").is(serviceName);
        Dashboard existing = mongoTemplate.findOne(new Query(match), Dashboard.class);

        String title = request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle().trim()
                : defaultTitle(request.getScope(), serviceName, environment);

        Dashboard dashboard = existing != null ? existing : new Dashboard();
        if (existing == null) {
            dashboard.setProjectId(projectId);
            dashboard.setEnvironment(environment);
            dashboard.setScope(request.getScope());
            dashboard.setServiceName(serviceName);
            dashboard.setCreatedBy(request.getCreatedBy() != null ? request.getCreatedBy() : "user");
            dashboard.setCreatedAt(Instant.now());
        }
        dashboard.setTitle(title);
        mongoTemplate.save(dashboard);

        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(DashboardView.of(dashboard));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(Scope.PROJECT) String projectId,
                                       @RequestHeader(Scope.ENVIRONMENT) String environment,
                                       @PathVariable String id) {
        mongoTemplate.remove(new Query(Criteria.where("_id").is(id)
                .and("project_id").is(projectId).and("environment").is(environment)), Dashboard.class);
        return ResponseEntity.noContent().build();
    }

    private static String defaultTitle(DashboardScope scope, String serviceName, String environment) {
        return scope == DashboardScope.SERVICE ? serviceName : environment + " overview";
    }
}
