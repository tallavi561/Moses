package com.moses.monitor.ingestion;

import com.moses.monitor.index.BucketKeyUtil;
import com.moses.monitor.model.LogEntry;
import com.moses.monitor.tokenize.Tokenizer;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Tokenizer tokenizer;
    private final IngestionBuffer buffer;
    private final MongoTemplate mongoTemplate;
    private final ExecutorService ingestionExecutor;
    private final int bucketGranularityMinutes;

    public IngestionService(Tokenizer tokenizer,
                             IngestionBuffer buffer,
                             MongoTemplate mongoTemplate,
                             ExecutorService ingestionExecutor,
                             @Value("${moses.index.bucket-granularity-minutes}") int bucketGranularityMinutes) {
        this.tokenizer = tokenizer;
        this.buffer = buffer;
        this.mongoTemplate = mongoTemplate;
        this.ingestionExecutor = ingestionExecutor;
        this.bucketGranularityMinutes = bucketGranularityMinutes;
    }

    /** Accepts a log, returns its generated id immediately; indexing happens asynchronously. */
    public String ingest(String serviceName, int logLevel, String ip, String rawMessage, Instant timestamp) {
        String logId = new ObjectId().toString();
        Instant effectiveTimestamp = timestamp != null ? timestamp : Instant.now();
        LogEntry entry = new LogEntry(logId, effectiveTimestamp, serviceName, logLevel, ip, rawMessage);

        ingestionExecutor.submit(() -> processAsync(entry));
        return logId;
    }

    private void processAsync(LogEntry entry) {
        try {
            mongoTemplate.save(entry);

            List<String> words = tokenizer.tokenize(entry.getRawMessage());
            String bucketKey = BucketKeyUtil.bucketKey(entry.getTimestamp(), bucketGranularityMinutes);
            Instant bucketStart = BucketKeyUtil.floorToBucket(entry.getTimestamp(), bucketGranularityMinutes);

            buffer.record(bucketKey, bucketStart, words, entry.getLogId());
        } catch (Exception e) {
            log.error("Failed to ingest log {}", entry.getLogId(), e);
        }
    }
}
