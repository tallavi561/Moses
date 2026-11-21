package com.moses.monitor.coverage;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which services in an environment have been sending logs lately, and how many of those logs carry
 * a trace id - the "logs" and "tracing" columns of the Monitoring access page. One aggregation over
 * the (project, environment, timestamp) index, cached for a minute per environment.
 */
@RestController
@RequestMapping("/api/search/coverage")
public class LogCoverageController {

    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 24 * 14;
    private static final long TTL_MS = 60_000;
    private static final int MAX_CACHED = 1_000;

    /** Logs one service sent in the window. */
    public record ServiceLogs(String serviceName, long logCount, long withTraceId, Instant lastLogAt) { }

    public record Coverage(int hours, List<ServiceLogs> services) { }

    private record Cached(Coverage value, long expiresAtMs) { }

    private final MongoTemplate mongoTemplate;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public LogCoverageController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Coverage coverage(@RequestHeader("X-Moses-Project-Id") String projectId,
                             @RequestHeader("X-Moses-Environment") String environment,
                             @RequestParam(defaultValue = "24") int hours) {
        int window = Math.max(1, Math.min(hours, MAX_HOURS));
        String key = projectId + "|" + environment + "|" + window;
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.value();
        }
        Coverage value = load(projectId, environment, window);
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Cached(value, now + TTL_MS));
        return value;
    }

    private Coverage load(String projectId, String environment, int hours) {
        Instant from = Instant.now().minus(Duration.ofHours(hours));
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                        .and("timestamp").gte(from)),
                Aggregation.group("service_name")
                        .count().as("logCount")
                        // $gt against null: true for any trace id present, false when missing or null.
                        .sum(context -> new Document("$cond", Arrays.asList(
                                new Document("$gt", Arrays.asList("$trace_id", null)), 1, 0))).as("withTraceId")
                        .max("timestamp").as("lastLogAt"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id"));
        List<ServiceLogs> services = mongoTemplate.aggregate(aggregation, "logs", Document.class).getMappedResults()
                .stream()
                .filter(d -> d.get("_id") instanceof String)
                .map(d -> new ServiceLogs(d.getString("_id"),
                        ((Number) d.get("logCount")).longValue(),
                        ((Number) d.get("withTraceId")).longValue(),
                        d.get("lastLogAt") instanceof Date date ? date.toInstant() : null))
                .toList();
        return new Coverage(hours, services);
    }
}
