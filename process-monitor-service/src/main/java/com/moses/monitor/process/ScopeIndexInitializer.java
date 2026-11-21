package com.moses.monitor.process;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** The indexes behind "this environment's services, state, connections, dashboards and metrics". */
@Component
public class ScopeIndexInitializer {

    private static final String METRICS_TTL_INDEX = "ttl_metric_samples_timestamp";

    private final MongoTemplate mongoTemplate;
    private final int metricsRetentionDays;

    public ScopeIndexInitializer(MongoTemplate mongoTemplate,
                                  @Value("${moses.metrics.retention-days}") int metricsRetentionDays) {
        this.mongoTemplate = mongoTemplate;
        this.metricsRetentionDays = metricsRetentionDays;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        // Services used to be per project; that unique index would now stop two environments
        // from having a service of the same name.
        dropIfPresent(ServiceDefinition.class, "project_name_unique");
        // Unique: two heartbeats racing to register the same new service must not create it twice.
        mongoTemplate.indexOps(ServiceDefinition.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .on("name", Sort.Direction.ASC).unique().named("project_environment_name_unique"));
        mongoTemplate.indexOps(ProcessState.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .named("idx_state_project_environment"));
        dropIfPresent(Connection.class, "idx_connections_project");
        mongoTemplate.indexOps(Connection.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .named("idx_connections_project_environment"));

        mongoTemplate.indexOps(Dashboard.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .named("idx_dashboards_project_environment"));

        mongoTemplate.indexOps(Incident.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .on("opened_at", Sort.Direction.DESC).named("idx_incidents_scope_opened"));
        mongoTemplate.indexOps(Incident.class).ensureIndex(
                new Index().on("state", Sort.Direction.ASC).named("idx_incidents_state"));
        mongoTemplate.indexOps(Incident.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("signatures", Sort.Direction.ASC)
                        .named("idx_incidents_project_signatures"));
        mongoTemplate.indexOps(TraceFlow.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .named("idx_trace_flows_scope"));
        mongoTemplate.indexOps(TraceFlow.class).ensureIndex(
                new Index().on("status", Sort.Direction.ASC).named("idx_trace_flows_status"));
        mongoTemplate.indexOps(AlertRule.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .on("service_name", Sort.Direction.ASC).unique().named("alert_rules_scope_service_unique"));

        mongoTemplate.indexOps(MetricSample.class).ensureIndex(
                new Index().on("project_id", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
                        .on("service_name", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC)
                        .named("idx_metrics_scope_timestamp"));
        ensureTtlIndex(MetricSample.class, METRICS_TTL_INDEX, "timestamp", Duration.ofDays(metricsRetentionDays));
    }

    /** Heartbeats are far more frequent than logs, so metrics get their own (shorter) TTL window. */
    private void ensureTtlIndex(Class<?> entityClass, String indexName, String field, Duration ttl) {
        IndexOperations indexOps = mongoTemplate.indexOps(entityClass);
        Optional<IndexInfo> existing = indexOps.getIndexInfo().stream()
                .filter(info -> info.getName().equals(indexName))
                .findFirst();
        boolean needsRecreate = existing.isPresent()
                && existing.get().getExpireAfter().map(d -> !d.equals(ttl)).orElse(true);
        if (existing.isEmpty() || needsRecreate) {
            existing.ifPresent(info -> indexOps.dropIndex(indexName));
            indexOps.ensureIndex(new Index().on(field, Sort.Direction.ASC).expire(ttl).named(indexName));
        }
    }

    private void dropIfPresent(Class<?> type, String indexName) {
        boolean present = mongoTemplate.indexOps(type).getIndexInfo().stream()
                .anyMatch(info -> indexName.equals(info.getName()));
        if (present) {
            mongoTemplate.indexOps(type).dropIndex(indexName);
        }
    }
}
