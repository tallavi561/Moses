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
        ProcessState existing = mongoTemplate.findById(request.getServiceName(), ProcessState.class);

        ProcessState state = new ProcessState();
        state.setServiceName(request.getServiceName());
        state.setProcessName(request.getProcessName());
        state.setDescription(request.getDescription());
        state.setStatus(request.getStatus());
        state.setCpuPercent(request.getCpuPercent());
        state.setMemoryMb(request.getMemoryMb());
        state.setProbeUrl(request.getProbeUrl());
        state.setLastSeen(Instant.now());
        carryOverProbeTelemetry(existing, state);

        mongoTemplate.save(state);
        syncConnections(request.getServiceName(), request.getConnections());
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
        updated.setDescription(request.getDescription());
        updated.setStatus(request.getStatus());
        updated.setCpuPercent(request.getCpuPercent());
        updated.setMemoryMb(request.getMemoryMb());
        updated.setProbeUrl(request.getProbeUrl());
        updated.setLastSeen(existing.getLastSeen());
        carryOverProbeTelemetry(existing, updated);
        mongoTemplate.save(updated);

        if (renamed) {
            mongoTemplate.remove(existing);
            renameServiceInConnections(serviceName, request.getServiceName());
        }

        syncConnections(request.getServiceName(), request.getConnections());
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

    /**
     * Probe telemetry (last probe time/status/latency/error) is written only by
     * {@link EndpointProber}, never by a heartbeat/edit request, so each request that rebuilds
     * the document from scratch needs to carry it forward - except when the probe URL itself
     * changed, in which case stale telemetry from the old target would be misleading.
     */
    private void carryOverProbeTelemetry(ProcessState existing, ProcessState updated) {
        if (existing == null) {
            return;
        }
        boolean sameProbeUrl = java.util.Objects.equals(existing.getProbeUrl(), updated.getProbeUrl());
        if (sameProbeUrl) {
            updated.setLastProbeAt(existing.getLastProbeAt());
            updated.setLastProbeStatusCode(existing.getLastProbeStatusCode());
            updated.setLastProbeLatencyMs(existing.getLastProbeLatencyMs());
            updated.setLastProbeError(existing.getLastProbeError());
        }
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

    /**
     * Makes {@code serviceName}'s connections match {@code desiredOtherNames} exactly: creates a
     * default connection for any new name, and removes any existing connection (in either
     * direction) to a name no longer in the list. {@code null} means "leave connections as they
     * are" (e.g. a heartbeat that doesn't mention connections at all).
     */
    private void syncConnections(String serviceName, List<String> desiredOtherNames) {
        if (desiredOtherNames == null) {
            return;
        }
        List<String> desired = desiredOtherNames.stream()
                .filter(name -> name != null && !name.isBlank() && !name.equals(serviceName))
                .toList();

        List<Connection> existing = mongoTemplate.find(
                new Query(new Criteria().orOperator(
                        Criteria.where("source_service").is(serviceName),
                        Criteria.where("target_service").is(serviceName))),
                Connection.class);

        for (Connection connection : existing) {
            String other = connection.getSourceService().equals(serviceName)
                    ? connection.getTargetService()
                    : connection.getSourceService();
            if (!desired.contains(other)) {
                mongoTemplate.remove(connection);
            }
        }

        for (String target : desired) {
            boolean exists = existing.stream().anyMatch(c ->
                    (c.getSourceService().equals(serviceName) && c.getTargetService().equals(target))
                            || (c.getSourceService().equals(target) && c.getTargetService().equals(serviceName)));
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
