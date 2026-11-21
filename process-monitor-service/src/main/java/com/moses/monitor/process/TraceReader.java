package com.moses.monitor.process;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads traces out of the shared {@code logs} collection: for every trace id in a time range,
 * which services took part, when each first logged, its first message and worst level.
 */
@Component
public class TraceReader {

    private final MongoTemplate mongoTemplate;

    public TraceReader(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    List<FlowAnalysis.TraceHops> read(String projectId, String environment, Instant from, Instant to, int limit) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("project_id", projectId).append("environment", environment)
                        .append("timestamp", new Document("$gte", from).append("$lte", to))
                        .append("trace_id", new Document("$nin", java.util.Arrays.asList(null, "")))),
                new Document("$sort", new Document("timestamp", 1)),
                new Document("$group", new Document("_id", new Document("tr", "$trace_id").append("s", "$service_name"))
                        .append("t", new Document("$min", "$timestamp"))
                        .append("m", new Document("$first", "$raw_message"))
                        .append("lv", new Document("$max", "$log_level"))),
                new Document("$group", new Document("_id", "$_id.tr")
                        .append("hops", new Document("$push", new Document("s", "$_id.s").append("t", "$t")
                                .append("m", "$m").append("lv", "$lv")))),
                new Document("$limit", limit));
        List<FlowAnalysis.TraceHops> traces = new ArrayList<>();
        for (Document row : mongoTemplate.getCollection("logs").aggregate(pipeline).allowDiskUse(true)) {
            List<FlowAnalysis.Hop> hops = new ArrayList<>();
            for (Document h : row.getList("hops", Document.class)) {
                if (h.getString("s") == null || h.getDate("t") == null) continue;
                Number lv = (Number) h.get("lv");
                hops.add(new FlowAnalysis.Hop(h.getString("s"), h.getDate("t").toInstant(), h.getString("m"),
                        lv == null ? 0 : lv.intValue()));
            }
            if (hops.isEmpty()) continue;
            hops.sort(Comparator.comparing(FlowAnalysis.Hop::at));
            traces.add(new FlowAnalysis.TraceHops(row.getString("_id"), hops));
        }
        return traces;
    }
}
