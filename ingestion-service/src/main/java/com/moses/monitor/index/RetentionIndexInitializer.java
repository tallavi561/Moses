package com.moses.monitor.index;

import com.moses.monitor.model.IndexBucket;
import com.moses.monitor.model.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Creates/refreshes the TTL indexes that implement data retention (open
 * point #4 in project_specification.md). Default retention is 14 days but
 * it is read from config so it can be tuned per deployment without a
 * redeploy of application logic, only a restart.
 */
@Component
public class RetentionIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(RetentionIndexInitializer.class);

    private static final String LOGS_TTL_INDEX = "ttl_logs_timestamp";
    private static final String BUCKETS_TTL_INDEX = "ttl_index_buckets_bucket_start";

    private final MongoTemplate mongoTemplate;
    private final int retentionDays;

    public RetentionIndexInitializer(MongoTemplate mongoTemplate,
                                      @Value("${moses.retention.days}") int retentionDays) {
        this.mongoTemplate = mongoTemplate;
        this.retentionDays = retentionDays;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTtlIndexes() {
        Duration ttl = Duration.ofDays(retentionDays);
        ensureTtlIndex(LogEntry.class, LOGS_TTL_INDEX, "timestamp", ttl);
        ensureTtlIndex(IndexBucket.class, BUCKETS_TTL_INDEX, "bucket_start", ttl);
        log.info("Retention TTL indexes ensured ({} days)", retentionDays);
    }

    private void ensureTtlIndex(Class<?> entityClass, String indexName, String field, Duration ttl) {
        IndexOperations indexOps = mongoTemplate.indexOps(entityClass);

        Optional<IndexInfo> existing = indexOps.getIndexInfo().stream()
                .filter(info -> info.getName().equals(indexName))
                .findFirst();

        boolean needsRecreate = existing.isPresent()
                && existing.get().getExpireAfter().map(d -> !d.equals(ttl)).orElse(true);

        if (existing.isEmpty() || needsRecreate) {
            existing.ifPresent(info -> indexOps.dropIndex(indexName));
            indexOps.ensureIndex(new Index().on(field, org.springframework.data.domain.Sort.Direction.ASC)
                    .expire(ttl)
                    .named(indexName));
        }
    }
}
