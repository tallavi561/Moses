package com.moses.monitor.search;

import com.moses.monitor.index.BucketKeyUtil;
import com.moses.monitor.tokenize.Tokenizer;
import com.moses.monitor.model.IndexBucket;
import com.moses.monitor.model.LogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Implements the search flow from project_specification.md:
 *  1. Macro-filter: pick relevant buckets for the time range, pull log_ids per word.
 *  2. Intersection: AND the id sets across all requested words.
 *  3. Micro-filter: re-check the exact timestamp range / level on the real documents.
 *  4. Fetch full documents from the Log Store.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final int MAX_BROWSE_RESULTS = 1000;
    private static final int MAX_TRACE_LOGS = 1000;

    private final MongoTemplate mongoTemplate;
    private final Tokenizer tokenizer;
    private final int bucketGranularityMinutes;

    public SearchService(MongoTemplate mongoTemplate,
                          Tokenizer tokenizer,
                          @Value("${moses.index.bucket-granularity-minutes}") int bucketGranularityMinutes) {
        this.mongoTemplate = mongoTemplate;
        this.tokenizer = tokenizer;
        this.bucketGranularityMinutes = bucketGranularityMinutes;
    }

    public List<LogEntry> search(SearchQuery query) {
        boolean noWords = query.getWords() == null || query.getWords().isEmpty();
        if (noWords) {
            // Browse (no words): no index lookup, just the newest logs in the time range,
            // optionally restricted to one service. Bucket keys are never built here - a huge
            // time range would otherwise allocate one key per bucket for nothing.
            return fetchAndMicroFilter(null, query);
        }

        List<String> bucketKeys = BucketKeyUtil.bucketKeysInRange(query.getFrom(), query.getTo(), bucketGranularityMinutes);
        if (bucketKeys.isEmpty()) {
            return List.of();
        }

        Set<String> candidateIds = resolveCandidateIds(query.getWords(), bucketKeys);
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        return fetchAndMicroFilter(candidateIds, query);
    }

    /**
     * Index phase: loads the id set of every word concurrently (each is an independent Mongo
     * round trip over up to one key per time bucket), then intersects them starting from the
     * smallest set so the work is bounded by the rarest word, not the most common one.
     */
    private Set<String> resolveCandidateIds(List<String> rawWords, List<String> bucketKeys) {
        long start = System.nanoTime();
        List<String> words = rawWords.stream().map(this::normalize).distinct().toList();

        List<Set<String>> idSets;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Set<String>>> futures = words.stream()
                    .map(word -> executor.submit(() -> idsForWordInBuckets(word, bucketKeys)))
                    .toList();
            idSets = new ArrayList<>();
            for (Future<Set<String>> future : futures) {
                idSets.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Search interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Index lookup failed", e.getCause());
        }

        idSets.sort(Comparator.comparingInt(Set::size));
        Set<String> candidates = new HashSet<>(idSets.get(0));
        for (int i = 1; i < idSets.size() && !candidates.isEmpty(); i++) {
            candidates.retainAll(idSets.get(i));
        }

        log.info("index phase: {} word(s) over {} bucket(s), set sizes {} -> {} candidate(s) in {} ms",
                words.size(), bucketKeys.size(), idSets.stream().map(Set::size).toList(),
                candidates.size(), (System.nanoTime() - start) / 1_000_000);
        return candidates;
    }

    /**
     * Cursor-paginated variant of {@link #search}: newest first, {@code limit} logs per call,
     * continuing strictly after the (before, beforeId) position when given. The index lookup
     * is repeated per call, but only {@code limit + 1} documents are ever fetched.
     */
    public LogPage searchPage(SearchQuery query, Instant before, String beforeId, int limit) {
        boolean noWords = query.getWords() == null || query.getWords().isEmpty();
        Set<String> candidateIds = null;
        if (!noWords) {
            List<String> bucketKeys = BucketKeyUtil.bucketKeysInRange(query.getFrom(), query.getTo(), bucketGranularityMinutes);
            if (bucketKeys.isEmpty()) {
                return new LogPage(List.of(), false);
            }
            candidateIds = resolveCandidateIds(query.getWords(), bucketKeys);
            if (candidateIds.isEmpty()) {
                return new LogPage(List.of(), false);
            }
        }

        List<Criteria> conditions = new ArrayList<>();
        conditions.add(Criteria.where("timestamp").gte(query.getFrom()).lte(query.getTo()));
        if (candidateIds != null) {
            conditions.add(Criteria.where("_id").in(candidateIds));
        }
        if (query.getLogLevel() != null) {
            conditions.add(Criteria.where("log_level").is(query.getLogLevel()));
        }
        if (query.getServiceName() != null) {
            conditions.add(Criteria.where("service_name").is(query.getServiceName()));
        }
        if (query.getTraceId() != null) {
            conditions.add(Criteria.where("trace_id").is(query.getTraceId()));
        }
        if (before != null) {
            Criteria older = Criteria.where("timestamp").lt(before);
            conditions.add(beforeId == null
                    ? older
                    : new Criteria().orOperator(older,
                            new Criteria().andOperator(
                                    Criteria.where("timestamp").is(before),
                                    Criteria.where("_id").lt(beforeId))));
        }

        Query mongoQuery = new Query(new Criteria().andOperator(conditions))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("timestamp"),
                        org.springframework.data.domain.Sort.Order.desc("_id")))
                .limit(limit + 1);

        long fetchStart = System.nanoTime();
        List<LogEntry> fetched = mongoTemplate.find(mongoQuery, LogEntry.class);
        log.info("fetch phase: {} log(s) in {} ms", fetched.size(), (System.nanoTime() - fetchStart) / 1_000_000);
        boolean hasMore = fetched.size() > limit;
        return new LogPage(hasMore ? fetched.subList(0, limit) : fetched, hasMore);
    }

    /**
     * Every log of one trace, oldest first, across all services and without a time window
     * (a trace is identified by its id alone; retention bounds how far back it can reach).
     */
    public List<LogEntry> trace(String traceId) {
        Query query = new Query(Criteria.where("trace_id").is(traceId))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.asc("timestamp"),
                        org.springframework.data.domain.Sort.Order.asc("_id")))
                .limit(MAX_TRACE_LOGS);
        long start = System.nanoTime();
        List<LogEntry> logs = mongoTemplate.find(query, LogEntry.class);
        log.info("trace lookup: {} log(s) in {} ms", logs.size(), (System.nanoTime() - start) / 1_000_000);
        return logs;
    }

    private String normalize(String rawWord) {
        List<String> tokens = tokenizer.tokenize(rawWord);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    private Set<String> idsForWordInBuckets(String word, List<String> bucketKeys) {
        Query q = Query.query(Criteria.where("word").is(word).and("bucket").in(bucketKeys));
        q.fields().include("log_ids");
        List<IndexBucket> buckets = mongoTemplate.find(q, IndexBucket.class);

        Set<String> ids = new HashSet<>();
        for (IndexBucket bucket : buckets) {
            ids.addAll(bucket.getLogIds());
        }
        return ids;
    }

    private List<LogEntry> fetchAndMicroFilter(Set<String> candidateIds, SearchQuery query) {
        Criteria criteria = Criteria.where("timestamp").gte(query.getFrom()).lte(query.getTo());
        if (candidateIds != null) {
            criteria = criteria.and("_id").in(candidateIds);
        }
        if (query.getLogLevel() != null) {
            criteria = criteria.and("log_level").is(query.getLogLevel());
        }
        if (query.getServiceName() != null) {
            criteria = criteria.and("service_name").is(query.getServiceName());
        }
        if (query.getTraceId() != null) {
            criteria = criteria.and("trace_id").is(query.getTraceId());
        }

        Query mongoQuery = Query.query(criteria);
        if (candidateIds == null) {
            // Newest N, re-sorted ascending below like every other result set.
            mongoQuery.with(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
            mongoQuery.limit(MAX_BROWSE_RESULTS);
        }
        List<LogEntry> results = mongoTemplate.find(mongoQuery, LogEntry.class);
        List<LogEntry> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(LogEntry::getTimestamp));
        return sorted;
    }
}
