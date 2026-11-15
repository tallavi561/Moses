package com.moses.monitor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * One document per (scope, word, time bucket). log_ids accumulate via upsert
 * so the array never grows unbounded for the lifetime of the index.
 *
 * The scope is one environment of one project ({@link #scopeOf}): each has its own index, so a
 * search never even looks at another project's words. The index itself is created by
 * ingestion-service's RetentionIndexInitializer (auto-index-creation is off there).
 */
@Document(collection = "index_buckets")
@CompoundIndexes({
        @CompoundIndex(name = "scope_word_bucket_unique", def = "{'scope': 1, 'word': 1, 'bucket': 1}", unique = true)
})
public class IndexBucket {

    @Id
    private String id;

    @Field("scope")
    private String scope;

    @Field("word")
    private String word;

    /** Bucket key, e.g. "2026-09-11_12:00" */
    @Field("bucket")
    private String bucket;

    /** Start instant of the bucket window, used for TTL cleanup. */
    @Field("bucket_start")
    private Instant bucketStart;

    @Field("log_ids")
    private Set<String> logIds = new HashSet<>();

    public IndexBucket() {
    }

    public IndexBucket(String scope, String word, String bucket, Instant bucketStart) {
        this.scope = scope;
        this.word = word;
        this.bucket = bucket;
        this.bucketStart = bucketStart;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public static String scopeOf(String projectId, String environment) {
        return projectId + "|" + environment;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    public void setBucketStart(Instant bucketStart) {
        this.bucketStart = bucketStart;
    }

    public Set<String> getLogIds() {
        return logIds;
    }

    public void setLogIds(Set<String> logIds) {
        this.logIds = logIds;
    }
}
