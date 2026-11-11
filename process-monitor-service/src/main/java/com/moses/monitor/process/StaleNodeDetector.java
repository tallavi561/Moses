package com.moses.monitor.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Marks nodes DOWN when nothing (heartbeat or log) has been received from them for too long.
 * Probed nodes are skipped: the prober already drives their status from real checks.
 */
@Component
public class StaleNodeDetector {

    private static final Logger log = LoggerFactory.getLogger(StaleNodeDetector.class);

    private final MongoTemplate mongoTemplate;
    private final Duration staleAfter;

    public StaleNodeDetector(MongoTemplate mongoTemplate,
                              @Value("${moses.liveness.stale-after-seconds}") long staleAfterSeconds) {
        this.mongoTemplate = mongoTemplate;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${moses.liveness.check-interval-ms}")
    public void markStaleNodesDown() {
        Instant cutoff = Instant.now().minus(staleAfter);
        Query stale = new Query(new Criteria().andOperator(
                Criteria.where("probe_url").in(null, ""),
                Criteria.where("last_seen").lt(cutoff),
                Criteria.where("status").ne(ProcessStatus.DOWN.name())));

        long modified = mongoTemplate.updateMulti(
                stale, new Update().set("status", ProcessStatus.DOWN.name()), ProcessState.class)
                .getModifiedCount();
        if (modified > 0) {
            log.info("Marked {} silent node(s) DOWN (no heartbeat/log for {}s)", modified, staleAfter.toSeconds());
        }
    }
}
