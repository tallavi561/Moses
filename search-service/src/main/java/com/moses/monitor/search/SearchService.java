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
            // Service-only browse: no index lookup, just the service's logs in the time range.
            return query.getServiceName() == null ? List.of() : fetchAndMicroFilter(null, query);
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
