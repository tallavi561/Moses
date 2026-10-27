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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Node (service) ingestion + read-back, plus node editing/renaming. Edges
 * between nodes are a separate concept - see {@link ConnectionController}.
 */
@RestController
@RequestMapping("/api/process")
public class ProcessStateController {

    private final MongoTemplate mongoTemplate;

    public ProcessStateController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        ProcessState state = new ProcessState();
        state.setServiceName(request.getServiceName());
        state.setProcessName(request.getProcessName());
        state.setStatus(request.getStatus());
        state.setCpuPercent(request.getCpuPercent());
        state.setMemoryMb(request.getMemoryMb());
        state.setLastSeen(Instant.now());

        mongoTemplate.save(state);
        ensureDefaultConnections(request.getServiceName(), request.getConnections());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/state")
    public List<ProcessState> currentState() {
        return mongoTemplate.findAll(ProcessState.class);
    }

    @PutMapping("/nodes/{serviceName}")
    public ResponseEntity<ProcessState> updateNode(@PathVariable String serviceName,
                                                     @Valid @RequestBody NodeUpdateRequest request) {
        ProcessState existing = mongoTemplate.findById(serviceName, ProcessState.class);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        boolean renamed = !serviceName.equals(request.getServiceName());

        ProcessState updated = new ProcessState();
        updated.setServiceName(request.getServiceName());
        updated.setProcessName(request.getProcessName());
        updated.setStatus(request.getStatus());
        updated.setCpuPercent(request.getCpuPercent());
        updated.setMemoryMb(request.getMemoryMb());
        updated.setLastSeen(existing.getLastSeen());
        mongoTemplate.save(updated);

        if (renamed) {
            mongoTemplate.remove(existing);
            renameServiceInConnections(serviceName, request.getServiceName());
        }

        ensureDefaultConnections(request.getServiceName(), request.getConnections());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/nodes/{serviceName}")
    public ResponseEntity<Void> deleteNode(@PathVariable String serviceName) {
        mongoTemplate.remove(new Query(Criteria.where("_id").is(serviceName)), ProcessState.class);
        mongoTemplate.remove(
                new Query(new Criteria().orOperator(
                        Criteria.where("source_service").is(serviceName),
                        Criteria.where("target_service").is(serviceName))),
                Connection.class);
        return ResponseEntity.noContent().build();
    }

    private void renameServiceInConnections(String oldName, String newName) {
        List<Connection> asSource = mongoTemplate.find(
                new Query(Criteria.where("source_service").is(oldName)), Connection.class);
        asSource.forEach(c -> {
            c.setSourceService(newName);
            mongoTemplate.save(c);
        });

        List<Connection> asTarget = mongoTemplate.find(
                new Query(Criteria.where("target_service").is(oldName)), Connection.class);
        asTarget.forEach(c -> {
            c.setTargetService(newName);
            mongoTemplate.save(c);
        });
    }

    /** For each target in {@code connectionTargets}, create a default connection if one doesn't already exist. */
    private void ensureDefaultConnections(String serviceName, List<String> connectionTargets) {
        if (connectionTargets == null) {
            return;
        }
        for (String target : connectionTargets) {
            if (target == null || target.isBlank() || target.equals(serviceName)) {
                continue;
            }
            boolean exists = mongoTemplate.exists(
                    new Query(new Criteria().orOperator(
                            Criteria.where("source_service").is(serviceName).and("target_service").is(target),
                            Criteria.where("source_service").is(target).and("target_service").is(serviceName))),
                    Connection.class);
            if (!exists) {
                Connection connection = new Connection();
                connection.setSourceService(serviceName);
                connection.setTargetService(target);
                connection.setDirection(ConnectionDirection.SOURCE_TO_TARGET);
                mongoTemplate.save(connection);
            }
        }
    }
}
