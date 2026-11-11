package com.moses.monitor.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A received log is proof the service is alive: refreshes last_seen on its node (and revives a
 * DOWN/UNKNOWN one to UP). Only touches nodes that already exist and have no probe URL - probed
 * nodes are owned by the process-monitor's prober. Writes are throttled per service so a log
 * burst costs one update, not one per log.
 */
@Component
public class ServiceLivenessTracker {

    private static final String COLLECTION = "process_state";

    private final MongoTemplate mongoTemplate;
    private final long throttleMs;
    private final ConcurrentHashMap<String, Long> lastTouch = new ConcurrentHashMap<>();

    public ServiceLivenessTracker(MongoTemplate mongoTemplate,
                                   @Value("${moses.ingestion.liveness-throttle-seconds:10}") long throttleSeconds) {
        this.mongoTemplate = mongoTemplate;
        this.throttleMs = throttleSeconds * 1000;
    }

    public void touch(String serviceName) {
        long now = System.currentTimeMillis();
        Long previous = lastTouch.get(serviceName);
        if (previous != null && now - previous < throttleMs) {
            return;
        }
        lastTouch.put(serviceName, now);

        Criteria unprobed = Criteria.where("_id").is(serviceName)
                .and("probe_url").in(null, "");

        mongoTemplate.updateFirst(
                new Query(new Criteria().andOperator(unprobed, Criteria.where("status").in("DOWN", "UNKNOWN"))),
                new Update().set("status", "UP").set("last_seen", Instant.now()),
                COLLECTION);
        mongoTemplate.updateFirst(
                new Query(unprobed),
                new Update().set("last_seen", Instant.now()),
                COLLECTION);
    }
}
