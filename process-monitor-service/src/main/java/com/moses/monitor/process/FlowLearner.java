package com.moses.monitor.process;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Learns the paths requests usually take from the last day of traces and stores them as
 * SUGGESTED {@link TraceFlow}s for the team to approve. Never activates anything by itself, and
 * never re-suggests a flow the team rejected; for flows it already knows it only refreshes the
 * numbers it saw.
 */
@Component
public class FlowLearner {

    private static final Logger log = LoggerFactory.getLogger(FlowLearner.class);
    private static final int MAX_TRACES = 20_000;
    private static final int MAX_NEW_SUGGESTIONS = 20;

    private final MongoTemplate mongoTemplate;
    private final TraceReader traces;
    private final Duration lookback;
    private final Duration minTraceAge;
    private final int minSamples;
    private final double minReachPercent;

    public FlowLearner(MongoTemplate mongoTemplate, TraceReader traces,
                       @Value("${moses.flows.lookback-hours:24}") int lookbackHours,
                       @Value("${moses.flows.min-trace-age-seconds:300}") int minTraceAgeSeconds,
                       @Value("${moses.flows.min-samples:30}") int minSamples,
                       @Value("${moses.flows.min-reach-percent:90}") double minReachPercent) {
        this.mongoTemplate = mongoTemplate;
        this.traces = traces;
        this.lookback = Duration.ofHours(Math.max(1, lookbackHours));
        this.minTraceAge = Duration.ofSeconds(Math.max(0, minTraceAgeSeconds));
        this.minSamples = Math.max(2, minSamples);
        this.minReachPercent = minReachPercent;
    }

    @Scheduled(initialDelayString = "${moses.flows.learn-initial-delay-ms:60000}",
            fixedDelayString = "${moses.flows.learn-interval-ms:600000}")
    public void learnEverywhere() {
        try {
            Instant now = Instant.now();
            List<Document> pipeline = List.of(
                    new Document("$match", new Document("timestamp", new Document("$gte", now.minus(lookback)))
                            .append("trace_id", new Document("$nin", java.util.Arrays.asList(null, "")))),
                    new Document("$group", new Document("_id", new Document("p", "$project_id").append("e", "$environment"))));
            for (Document row : mongoTemplate.getCollection("logs").aggregate(pipeline)) {
                Document id = row.get("_id", Document.class);
                if (id.getString("p") != null && id.getString("e") != null) {
                    learn(id.getString("p"), id.getString("e"), now);
                }
            }
        } catch (RuntimeException e) {
            log.warn("flow learning failed: {}", e.toString());
        }
    }

    int minSamples() {
        return minSamples;
    }

    /** Learns one environment now; returns how many new suggestions it added. */
    int learn(String projectId, String environment, Instant now) {
        List<FlowAnalysis.TraceHops> seen = traces.read(projectId, environment, now.minus(lookback), now.minus(minTraceAge), MAX_TRACES);
        int added = 0;
        for (FlowAnalysis.Suggestion s : FlowAnalysis.suggest(seen, minSamples, minReachPercent)) {
            Query same = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                    .and("entry_service").is(s.entryService()).and("route").is(s.route())
                    .and("target_service").is(s.targetService()));
            TraceFlow flow = mongoTemplate.findOne(same, TraceFlow.class);
            if (flow == null) {
                if (added >= MAX_NEW_SUGGESTIONS) continue;
                flow = new TraceFlow();
                flow.setProjectId(projectId);
                flow.setEnvironment(environment);
                flow.setEntryService(s.entryService());
                flow.setRoute(s.route());
                flow.setRouteExample(s.routeExample());
                flow.setTargetService(s.targetService());
                flow.setMaxSeconds(FlowAnalysis.suggestedMaxSeconds(s.p95Seconds()));
                flow.setStatus(TraceFlowStatus.SUGGESTED);
                flow.setCreatedBy("moses");
                flow.setCreatedAt(now);
                added++;
            }
            flow.setLearnedSamples(s.samples());
            flow.setLearnedReachPercent(s.reachPercent());
            flow.setLearnedP95Seconds(s.p95Seconds());
            flow.setExampleTraceId(s.exampleTraceId());
            flow.setLearnedAt(now);
            mongoTemplate.save(flow);
        }
        return added;
    }
}
