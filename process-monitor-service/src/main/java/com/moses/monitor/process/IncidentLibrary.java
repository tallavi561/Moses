package com.moses.monitor.process;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Past incidents as a knowledge base: "have we seen this before, and what did we do?"
 *
 * Two incidents are similar when they share a kind of error ({@link ErrorSignature}) - in any
 * service, since the same failure often shows up wherever a shared dependency is used - or when
 * the same service went DOWN before. Matching is across all environments of the project: a fix
 * found in staging is just as useful in prod. No AI involved.
 */
@Component
public class IncidentLibrary {

    static final String DOWN_SIGNATURE = "<service down>";
    static final int SIMILAR_LIMIT = 5;
    static final int MAX_SEARCH = 200;

    private final MongoTemplate mongoTemplate;

    public IncidentLibrary(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Earlier incidents like this one, reviewed ones first, then the most recent. */
    List<Incident> similar(Incident incident, int limit) {
        Criteria match;
        if (incident.getKind() == IncidentKind.SERVICE_DOWN) {
            match = Criteria.where("kind").is(IncidentKind.SERVICE_DOWN).and("service_name").is(incident.getServiceName());
        } else if (!incident.getSignatures().isEmpty()) {
            match = Criteria.where("signatures").in(incident.getSignatures().stream()
                    .filter(s -> !DOWN_SIGNATURE.equals(s)).toList());
        } else {
            return List.of();
        }
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("project_id").is(incident.getProjectId()),
                match,
                Criteria.where("opened_at").lt(incident.getOpenedAt()));
        if (incident.getId() != null) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("_id").ne(incident.getId()));
        }
        List<Incident> found = mongoTemplate.find(new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "opened_at")).limit(MAX_SEARCH), Incident.class);
        return found.stream()
                .sorted((a, b) -> {
                    int ra = a.getReviewStatus() == IncidentReview.UNREVIEWED ? 1 : 0;
                    int rb = b.getReviewStatus() == IncidentReview.UNREVIEWED ? 1 : 0;
                    return ra != rb ? Integer.compare(ra, rb) : b.getOpenedAt().compareTo(a.getOpenedAt());
                })
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * The library: incidents of the project (or one environment), optionally only those with a
     * given review status, of a service, or containing some text in the service name, the errors
     * or what the team wrote.
     */
    List<Incident> search(String projectId, String environment, String text, IncidentReview status,
                          String service, int limit) {
        Criteria criteria = Criteria.where("project_id").is(projectId);
        if (environment != null) {
            criteria = criteria.and("environment").is(environment);
        }
        if (status != null) {
            criteria = status == IncidentReview.UNREVIEWED
                    ? criteria.and("review_status").in(IncidentReview.UNREVIEWED, null)
                    : criteria.and("review_status").is(status);
        }
        if (service != null && !service.isBlank()) {
            criteria = criteria.and("service_name").is(service.trim());
        }
        Query query;
        if (text != null && !text.isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(text.trim()), Pattern.CASE_INSENSITIVE);
            query = new Query(new Criteria().andOperator(criteria, new Criteria().orOperator(
                    Criteria.where("service_name").regex(p),
                    Criteria.where("root_cause").regex(p),
                    Criteria.where("resolution").regex(p),
                    Criteria.where("top_errors.message").regex(p),
                    Criteria.where("ai_investigation.summary").regex(p),
                    Criteria.where("ai_investigation.rootCause").regex(p))));
        } else {
            query = new Query(criteria);
        }
        return mongoTemplate.find(query.with(Sort.by(Sort.Direction.DESC, "opened_at"))
                .limit(Math.max(1, Math.min(limit, MAX_SEARCH))), Incident.class);
    }
}
