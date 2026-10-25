package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * SKELETON. Minimal heartbeat ingestion + state read-back so the React flow
 * diagram has something real to render. No history/alerting yet - just
 * "what does the topology look like right now".
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
        state.setCalls(request.getCalls());
        state.setLastSeen(Instant.now());

        mongoTemplate.save(state);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/state")
    public List<ProcessState> currentState() {
        return mongoTemplate.findAll(ProcessState.class);
    }
}
