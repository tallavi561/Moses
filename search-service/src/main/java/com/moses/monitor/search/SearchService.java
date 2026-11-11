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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implements the search flow from project_specification.md:
 *  1. Macro-filter: pick relevant buckets for the time range, pull log_ids per word.
 *  2. Intersection: AND the id sets across all requested words.
 *  3. Micro-filter: re-check the exact timestamp range / level on the real documents.
 *  4. Fetch full documents from the Log Store.
 */
@Service
public class SearchService {

    private static final int MAX_BROWSE_RESULTS = 1000;

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
        List<String> bucketKeys = BucketKeyUtil.bucketKeysInRange(query.getFrom(), query.getTo(), bucketGranularityMinutes);
        if (bucketKeys.isEmpty()) {
            return List.of();
        }

        boolean noWords = query.getWords() == null || query.getWords().isEmpty();
        if (noWords) {
            // Browse (no words): no index lookup, just the newest logs in the time range,
            // optionally restricted to one service.
            return fetchAndMicroFilter(null, query);
        }

        Set<String> candidateIds = null;
        for (String rawWord : query.getWords()) {
            String normalizedWord = normalize(rawWord);
            Set<String> idsForWord = idsForWordInBuckets(normalizedWord, bucketKeys);

            if (candidateIds == null) {
                candidateIds = idsForWord;
            } else {
                candidateIds.retainAll(idsForWord);
            }
            if (candidateIds.isEmpty()) {
                return List.of();
            }
        }

        return fetchAndMicroFilter(candidateIds, query);
    }

    /**
     * Cursor-paginated variant of {@link #search}: newest first, {@code limit} logs per call,
     * continuing strictly after the (before, beforeId) position when given. The index lookup
     * is repeated per call, but only {@code limit + 1} documents are ever fetched.
     */
    public LogPage searchPage(SearchQuery query, Instant before, String beforeId, int limit) {
        List<String> bucketKeys = BucketKeyUtil.bucketKeysInRange(query.getFrom(), query.getTo(), bucketGranularityMinutes);
        if (bucketKeys.isEmpty()) {
            return new LogPage(List.of(), false);
        }

        boolean noWords = query.getWords() == null || query.getWords().isEmpty();
        Set<String> candidateIds = null;
        if (!noWords) {
            for (String rawWord : query.getWords()) {
                Set<String> idsForWord = idsForWordInBuckets(normalize(rawWord), bucketKeys);
                if (candidateIds == null) {
                    candidateIds = idsForWord;
                } else {
                    candidateIds.retainAll(idsForWord);
                }
                if (candidateIds.isEmpty()) {
                    return new LogPage(List.of(), false);
                }
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

        List<LogEntry> fetched = mongoTemplate.find(mongoQuery, LogEntry.class);
        boolean hasMore = fetched.size() > limit;
        return new LogPage(hasMore ? fetched.subList(0, limit) : fetched, hasMore);
    }

    private String normalize(String rawWord) {
        List<String> tokens = tokenizer.tokenize(rawWord);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    private Set<String> idsForWordInBuckets(String word, List<String> bucketKeys) {
        Query q = Query.query(Criteria.where("word").is(word).and("bucket").in(bucketKeys));
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
