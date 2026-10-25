package com.moses.monitor.index;

import com.moses.monitor.ingestion.IngestionBuffer;
import com.moses.monitor.model.IndexBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically drains the IngestionBuffer and bulk-upserts the accumulated
 * (word, bucket) -> log_ids additions into the index_buckets collection.
 * Using $addToSet means repeated flushes are idempotent and never duplicate
 * a log id even if retried.
 */
@Component
public class IndexFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexFlushScheduler.class);

    private final IngestionBuffer buffer;
    private final MongoTemplate mongoTemplate;

    public IndexFlushScheduler(IngestionBuffer buffer, MongoTemplate mongoTemplate) {
        this.buffer = buffer;
        this.mongoTemplate = mongoTemplate;
    }

    @Scheduled(fixedDelayString = "#{${moses.ingestion.flush-interval-seconds} * 1000}")
    public void flush() {
        Map<String, IngestionBuffer.BucketEntry> snapshot = buffer.drainAndSwap();
        if (snapshot.isEmpty()) {
            return;
        }

        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, IndexBucket.class);
        int opCount = 0;

        for (Map.Entry<String, IngestionBuffer.BucketEntry> bucketEntry : snapshot.entrySet()) {
            String bucketKey = bucketEntry.getKey();
            IngestionBuffer.BucketEntry data = bucketEntry.getValue();

            for (Map.Entry<String, Set<String>> wordEntry : data.getWordToLogIds().entrySet()) {
                String word = wordEntry.getKey();
                List<String> ids = List.copyOf(wordEntry.getValue());
                if (ids.isEmpty()) {
                    continue;
                }

                Query query = Query.query(Criteria.where("word").is(word).and("bucket").is(bucketKey));
                Update update = new Update()
                        .addToSet("log_ids").each(ids.toArray())
                        .setOnInsert("bucket_start", data.getBucketStart());

                bulkOps.upsert(query, update);
                opCount++;
            }
        }

        if (opCount > 0) {
            bulkOps.execute();
            log.debug("Flushed {} index bucket upserts", opCount);
        }
    }
}
