package com.moses.monitor.process;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CPU/memory history for the Grafana-style chart on the Dashboard page (and the chat agent's
 * get_metrics tool): one series of {@link MetricPoint}s per service, oldest first.
 */
@RestController
@RequestMapping("/api/process/metrics")
public class MetricsController {

    private static final int MAX_MINUTES_BACK = (int) Duration.ofDays(7).toMinutes();
    private static final int MAX_POINTS_PER_SERVICE = 500;

    private final MongoTemplate mongoTemplate;

    public MetricsController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public List<ServiceMetrics> history(@RequestHeader(Scope.PROJECT) String projectId,
                                        @RequestHeader(Scope.ENVIRONMENT) String environment,
                                        @RequestParam(required = false) List<String> service,
                                        @RequestParam(required = false) Integer minutesBack) {
        int minutes = Math.max(1, Math.min(minutesBack == null ? 60 : minutesBack, MAX_MINUTES_BACK));
        Instant from = Instant.now().minus(Duration.ofMinutes(minutes));

        List<String> serviceNames = service != null && !service.isEmpty()
                ? service
                : mongoTemplate.find(
                                new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                                ServiceDefinition.class).stream()
                        .map(ServiceDefinition::getName)
                        .toList();

        return serviceNames.stream().map(name -> new ServiceMetrics(name, samplesFor(projectId, environment, name, from)))
                .toList();
    }

    private List<MetricPoint> samplesFor(String projectId, String environment, String serviceName, Instant from) {
        Query query = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                .and("service_name").is(serviceName).and("timestamp").gte(from))
                .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .limit(MAX_POINTS_PER_SERVICE);
        List<MetricSample> samples = mongoTemplate.find(query, MetricSample.class);
        return samples.stream()
                .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .map(MetricPoint::of)
                .collect(Collectors.toList());
    }
}
