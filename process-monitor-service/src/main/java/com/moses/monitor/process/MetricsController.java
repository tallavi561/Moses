package com.moses.monitor.process;

import org.bson.Document;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final MetricsCache cache;

    public MetricsController(MongoTemplate mongoTemplate, MetricsCache cache) {
        this.mongoTemplate = mongoTemplate;
        this.cache = cache;
    }

    /**
     * Served from {@link MetricsCache}: the whole environment is loaded once per range and cache
     * lifetime, and a request for some services is a filter over that.
     */
    @GetMapping
    public List<ServiceMetrics> history(@RequestHeader(Scope.PROJECT) String projectId,
                                        @RequestHeader(Scope.ENVIRONMENT) String environment,
                                        @RequestParam(required = false) List<String> service,
                                        @RequestParam(required = false) Integer minutesBack) {
        int minutes = Math.max(1, Math.min(minutesBack == null ? 60 : minutesBack, MAX_MINUTES_BACK));
        Map<String, List<MetricPoint>> all = cache.get(projectId, environment, minutes,
                () -> loadAll(projectId, environment, minutes));
        if (service == null || service.isEmpty()) {
            return all.entrySet().stream().map(e -> new ServiceMetrics(e.getKey(), e.getValue())).toList();
        }
        Instant from = Instant.now().minus(Duration.ofMinutes(minutes));
        // A service registered after the cached copy was taken is read directly.
        return service.stream().map(name -> new ServiceMetrics(name,
                all.containsKey(name) ? all.get(name) : samplesFor(projectId, environment, name, from))).toList();
    }

    /**
     * ERROR/FATAL logs per minute for every service of the environment, counted from the logs
     * themselves (no sender needs to report anything extra). Buckets grow with the range so a chart
     * has about 60-170 points; every bucket is present, zero included, so the line drops to 0
     * instead of skipping quiet stretches.
     */
    @GetMapping("/errors")
    public List<ServiceMetrics> errorRates(@RequestHeader(Scope.PROJECT) String projectId,
                                           @RequestHeader(Scope.ENVIRONMENT) String environment,
                                           @RequestParam(required = false) List<String> service,
                                           @RequestParam(required = false) Integer minutesBack) {
        int minutes = Math.max(1, Math.min(minutesBack == null ? 60 : minutesBack, MAX_MINUTES_BACK));
        Map<String, List<MetricPoint>> all = cache.get("errors", projectId, environment, minutes,
                () -> loadErrorRates(projectId, environment, minutes));
        if (service == null || service.isEmpty()) {
            return all.entrySet().stream().map(e -> new ServiceMetrics(e.getKey(), e.getValue())).toList();
        }
        return service.stream().filter(all::containsKey).map(name -> new ServiceMetrics(name, all.get(name))).toList();
    }

    /** Bucket size in minutes for a range: 1 up to an hour, then 5, 15, 60. */
    static int errorBucketMinutes(int minutesBack) {
        if (minutesBack <= 60) return 1;
        if (minutesBack <= 360) return 5;
        if (minutesBack <= 1440) return 15;
        return 60;
    }

    private Map<String, List<MetricPoint>> loadErrorRates(String projectId, String environment, int minutes) {
        int bucketMinutes = errorBucketMinutes(minutes);
        long bucketMs = Duration.ofMinutes(bucketMinutes).toMillis();
        long nowMs = System.currentTimeMillis();
        long lastBucket = nowMs - Math.floorMod(nowMs, bucketMs);
        long firstBucket = lastBucket - ((long) Math.ceil((double) minutes / bucketMinutes) - 1) * bucketMs;
        Instant from = Instant.ofEpochMilli(firstBucket);

        Map<String, Map<Long, Long>> counts = new TreeMap<>();
        mongoTemplate.find(new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                ServiceDefinition.class).forEach(s -> counts.put(s.getName(), new HashMap<>()));
        Document bucketOf = new Document("$subtract", List.of(new Document("$toLong", "$timestamp"),
                new Document("$mod", List.of(new Document("$toLong", "$timestamp"), bucketMs))));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("project_id", projectId).append("environment", environment)
                        .append("timestamp", new Document("$gte", from))
                        .append("log_level", new Document("$gte", IncidentDetector.ERROR_LEVEL))),
                new Document("$group", new Document("_id", new Document("s", "$service_name").append("b", bucketOf))
                        .append("n", new Document("$sum", 1))));
        for (Document row : mongoTemplate.getCollection("logs").aggregate(pipeline)) {
            Document id = row.get("_id", Document.class);
            String name = id.getString("s");
            if (name == null) continue;
            counts.computeIfAbsent(name, k -> new HashMap<>())
                    .put(((Number) id.get("b")).longValue(), ((Number) row.get("n")).longValue());
        }

        Map<String, List<MetricPoint>> all = new LinkedHashMap<>();
        counts.forEach((name, byBucket) -> {
            List<MetricPoint> points = new ArrayList<>();
            for (long b = firstBucket; b <= lastBucket; b += bucketMs) {
                points.add(MetricPoint.errors(Instant.ofEpochMilli(b), byBucket.getOrDefault(b, 0L) / (double) bucketMinutes));
            }
            all.put(name, List.copyOf(points));
        });
        return Collections.unmodifiableMap(all);
    }

    private Map<String, List<MetricPoint>> loadAll(String projectId, String environment, int minutes) {
        Instant from = Instant.now().minus(Duration.ofMinutes(minutes));
        Map<String, List<MetricPoint>> all = new LinkedHashMap<>();
        mongoTemplate.find(new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                        ServiceDefinition.class).stream()
                .map(ServiceDefinition::getName)
                .sorted()
                .forEach(name -> all.put(name, List.copyOf(samplesFor(projectId, environment, name, from))));
        return Collections.unmodifiableMap(all);
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
