package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for edges between nodes. A connection is normally created implicitly
 * via a node's "connections" list (see ProcessStateController), but can also
 * be created and edited directly here - this is how direction/communication
 * type/description get set after the fact. Connections belong to one environment
 * of a project, like the services they join.
 */
@RestController
@RequestMapping("/api/process/connections")
public class ConnectionController {

    private final MongoTemplate mongoTemplate;

    public ConnectionController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public List<Connection> listConnections(@RequestHeader(Scope.PROJECT) String projectId,
                                            @RequestHeader(Scope.ENVIRONMENT) String environment) {
        return mongoTemplate.find(new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                Connection.class);
    }

    @PostMapping
    public ResponseEntity<Connection> createConnection(@RequestHeader(Scope.PROJECT) String projectId,
                                                         @RequestHeader(Scope.ENVIRONMENT) String environment,
                                                         @Valid @RequestBody ConnectionRequest request) {
        Connection connection = new Connection();
        connection.setId(UUID.randomUUID().toString());
        connection.setProjectId(projectId);
        connection.setEnvironment(environment);
        apply(connection, request);
        mongoTemplate.save(connection);
        return ResponseEntity.ok(connection);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Connection> updateConnection(@RequestHeader(Scope.PROJECT) String projectId,
                                                         @RequestHeader(Scope.ENVIRONMENT) String environment,
                                                         @PathVariable String id,
                                                         @Valid @RequestBody ConnectionRequest request) {
        Connection existing = findInEnvironment(projectId, environment, id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        apply(existing, request);
        mongoTemplate.save(existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnection(@RequestHeader(Scope.PROJECT) String projectId,
                                                   @RequestHeader(Scope.ENVIRONMENT) String environment,
                                                   @PathVariable String id) {
        Connection existing = findInEnvironment(projectId, environment, id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        mongoTemplate.remove(existing);
        return ResponseEntity.noContent().build();
    }

    /** Another project's or environment's connection is simply "not found" here. */
    private Connection findInEnvironment(String projectId, String environment, String id) {
        return mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(id).and("project_id").is(projectId).and("environment").is(environment)),
                Connection.class);
    }

    private void apply(Connection connection, ConnectionRequest request) {
        connection.setSourceService(request.getSourceService());
        connection.setTargetService(request.getTargetService());
        connection.setDirection(request.getDirection());
        connection.setCommunicationType(request.getCommunicationType());
        connection.setDescription(request.getDescription());
    }
}
