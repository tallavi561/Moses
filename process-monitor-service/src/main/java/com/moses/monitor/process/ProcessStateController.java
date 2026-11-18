package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Node (service) ingestion + read-back, plus node editing/renaming. Edges
 * between nodes are a separate concept - see {@link ConnectionController}.
 *
 * Every request is about one environment of one project (see {@link Scope}), and everything
 * here belongs to that environment: like a namespace in OpenShift, each environment has its own
 * services, connections and state. Adding, renaming or deleting a service in prod leaves staging
 * untouched.
 */
@RestController
@RequestMapping("/api/process")
public class ProcessStateController {

    private final MongoTemplate mongoTemplate;

    public ProcessStateController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestHeader(Scope.PROJECT) String projectId,
                                          @RequestHeader(Scope.ENVIRONMENT) String environment,
                                          @Valid @RequestBody HeartbeatRequest request) {
        String serviceName = request.getServiceName();
        registerDefinition(projectId, environment, serviceName, request.getProcessName(), request.getDescription());

        ProcessState existing = findState(projectId, environment, serviceName);
        ProcessState state = new ProcessState(projectId, environment, serviceName);
        state.setStatus(request.getStatus());
        state.setCpuPercent(request.getCpuPercent());
        state.setMemoryMb(request.getMemoryMb());
        state.setProbeUrl(request.getProbeUrl());
        state.setLastSeen(Instant.now());
        carryOverProbeTelemetry(existing, state);

        mongoTemplate.save(state);
        syncConnections(projectId, environment, serviceName, request.getConnections());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/state")
    public List<NodeView> currentState(@RequestHeader(Scope.PROJECT) String projectId,
                                       @RequestHeader(Scope.ENVIRONMENT) String environment) {
        Map<String, ProcessState> states = mongoTemplate.find(
                        new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                        ProcessState.class).stream()
                .collect(Collectors.toMap(ProcessState::getServiceName, Function.identity(), (a, b) -> a));

        Query definitions = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment))
                .with(Sort.by(Sort.Direction.ASC, "name"));
        return mongoTemplate.find(definitions, ServiceDefinition.class).stream()
                .map(definition -> NodeView.of(definition, states.get(definition.getName())))
                .toList();
    }

    @PutMapping("/nodes/{serviceName}")
    public ResponseEntity<NodeView> updateNode(@RequestHeader(Scope.PROJECT) String projectId,
                                               @RequestHeader(Scope.ENVIRONMENT) String environment,
                                               @PathVariable String serviceName,
                                               @Valid @RequestBody NodeUpdateRequest request) {
        ServiceDefinition definition = findDefinition(projectId, environment, serviceName);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }

        String newName = request.getServiceName();
        boolean renamed = !serviceName.equals(newName);
        if (renamed && findDefinition(projectId, environment, newName) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        definition.setName(newName);
        definition.setProcessName(request.getProcessName());
        definition.setDescription(request.getDescription());
        mongoTemplate.save(definition);
        if (renamed) {
            renameServiceInStates(projectId, environment, serviceName, newName);
            renameServiceInConnections(projectId, environment, serviceName, newName);
        }

        ProcessState existing = findState(projectId, environment, newName);
        ProcessState updated = new ProcessState(projectId, environment, newName);
        updated.setStatus(request.getStatus());
        if (existing != null) {
            // The edit form doesn't carry metrics; keep what the service last reported.
            updated.setCpuPercent(request.getCpuPercent() != null ? request.getCpuPercent() : existing.getCpuPercent());
            updated.setMemoryMb(request.getMemoryMb() != null ? request.getMemoryMb() : existing.getMemoryMb());
            updated.setLastSeen(existing.getLastSeen());
        } else {
            updated.setCpuPercent(request.getCpuPercent());
            updated.setMemoryMb(request.getMemoryMb());
        }
        updated.setProbeUrl(request.getProbeUrl());
        carryOverProbeTelemetry(existing, updated);
        mongoTemplate.save(updated);

        syncConnections(projectId, environment, newName, request.getConnections());
        return ResponseEntity.ok(NodeView.of(definition, updated));
    }

    /** Removes the service from this environment: its definition, its state, its connections. */
    @DeleteMapping("/nodes/{serviceName}")
    public ResponseEntity<Void> deleteNode(@RequestHeader(Scope.PROJECT) String projectId,
                                           @RequestHeader(Scope.ENVIRONMENT) String environment,
                                           @PathVariable String serviceName) {
        mongoTemplate.remove(
                new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                        .and("name").is(serviceName)),
                ServiceDefinition.class);
        mongoTemplate.remove(findStateQuery(projectId, environment, serviceName), ProcessState.class);
        mongoTemplate.remove(new Query(touching(projectId, environment, serviceName)), Connection.class);
        return ResponseEntity.noContent().build();
    }

    private ServiceDefinition findDefinition(String projectId, String environment, String name) {
        return mongoTemplate.findOne(
                new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                        .and("name").is(name)), ServiceDefinition.class);
    }

    private static Query findStateQuery(String projectId, String environment, String serviceName) {
        return new Query(Criteria.where("_id").is(ProcessState.idOf(projectId, environment, serviceName)));
    }

    private ProcessState findState(String projectId, String environment, String serviceName) {
        return mongoTemplate.findById(ProcessState.idOf(projectId, environment, serviceName), ProcessState.class);
    }

    /**
     * Makes sure the environment knows the service, creating it on its first heartbeat there. A heartbeat
     * that leaves out the process name or description keeps what is already there - services
     * report in constantly, and most of them only say "I'm up".
     */
    private void registerDefinition(String projectId, String environment, String name, String processName,
                                    String description) {
        Query query = new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)
                .and("name").is(name));
        Update update = new Update().setOnInsert("project_id", projectId).setOnInsert("environment", environment)
                .setOnInsert("name", name);
        if (processName != null) {
            update.set("process_name", processName);
        }
        if (description != null) {
            update.set("description", description);
        }
        try {
            mongoTemplate.upsert(query, update, ServiceDefinition.class);
        } catch (DuplicateKeyException e) {
            // Two first heartbeats raced and the other one created it; now it is a plain update.
            mongoTemplate.upsert(query, update, ServiceDefinition.class);
        }
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
        boolean sameProbeUrl = Objects.equals(existing.getProbeUrl(), updated.getProbeUrl());
        if (sameProbeUrl) {
            updated.setLastProbeAt(existing.getLastProbeAt());
            updated.setLastProbeStatusCode(existing.getLastProbeStatusCode());
            updated.setLastProbeLatencyMs(existing.getLastProbeLatencyMs());
            updated.setLastProbeError(existing.getLastProbeError());
        }
    }

    /** The state's id contains the service name, so a rename re-creates the document. */
    private void renameServiceInStates(String projectId, String environment, String oldName, String newName) {
        List<ProcessState> states = mongoTemplate.find(findStateQuery(projectId, environment, oldName),
                ProcessState.class);
        for (ProcessState state : states) {
            mongoTemplate.remove(state);
            state.setId(ProcessState.idOf(projectId, state.getEnvironment(), newName));
            state.setServiceName(newName);
            mongoTemplate.save(state);
        }
    }

    private void renameServiceInConnections(String projectId, String environment, String oldName, String newName) {
        Criteria inProject = Criteria.where("project_id").is(projectId).and("environment").is(environment);
        mongoTemplate.updateMulti(
                new Query(new Criteria().andOperator(inProject, Criteria.where("source_service").is(oldName))),
                new Update().set("source_service", newName), Connection.class);
        mongoTemplate.updateMulti(
                new Query(new Criteria().andOperator(inProject, Criteria.where("target_service").is(oldName))),
                new Update().set("target_service", newName), Connection.class);
    }

    /** Every connection of the environment that has the service on either end. */
    private static Criteria touching(String projectId, String environment, String serviceName) {
        return new Criteria().andOperator(
                Criteria.where("project_id").is(projectId).and("environment").is(environment),
                new Criteria().orOperator(
                        Criteria.where("source_service").is(serviceName),
                        Criteria.where("target_service").is(serviceName)));
    }

    /**
     * Makes {@code serviceName}'s connections match {@code desiredOtherNames} exactly: creates a
     * default connection for any new name, and removes any existing connection (in either
     * direction) to a name no longer in the list. {@code null} means "leave connections as they
     * are" (e.g. a heartbeat that doesn't mention connections at all).
     */
    private void syncConnections(String projectId, String environment, String serviceName,
                                 List<String> desiredOtherNames) {
        if (desiredOtherNames == null) {
            return;
        }
        List<String> desired = desiredOtherNames.stream()
                .filter(name -> name != null && !name.isBlank() && !name.equals(serviceName))
                .toList();

        List<Connection> existing = mongoTemplate.find(new Query(touching(projectId, environment, serviceName)),
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
                connection.setProjectId(projectId);
                connection.setEnvironment(environment);
                connection.setSourceService(serviceName);
                connection.setTargetService(target);
                connection.setDirection(ConnectionDirection.SOURCE_TO_TARGET);
                mongoTemplate.save(connection);
            }
        }
    }
}
