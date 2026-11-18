package com.moses.monitor.process;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** The indexes behind "this environment's services, state and connections". */
@Component
public class ScopeIndexInitializer {

    private final MongoTemplate mongoTemplate;

    public ScopeIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
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
    }

    private void dropIfPresent(Class<?> type, String indexName) {
        boolean present = mongoTemplate.indexOps(type).getIndexInfo().stream()
                .anyMatch(info -> indexName.equals(info.getName()));
        if (present) {
            mongoTemplate.indexOps(type).dropIndex(indexName);
        }
    }
}
