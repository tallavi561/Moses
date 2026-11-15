package com.moses.monitor.web;

import com.moses.monitor.ingestion.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * The two headers are set by api-gateway from the sender's API key (or, for a signed-in
     * user, from the project and environment they are working in) - they say who owns the log.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@RequestHeader("X-Moses-Project-Id") String projectId,
                                                      @RequestHeader("X-Moses-Environment") String environment,
                                                      @Valid @RequestBody IncomingLogRequest request) {
        String logId = ingestionService.ingest(
                projectId,
                environment,
                request.getServiceName(),
                request.getLogLevel(),
                request.getIp(),
                request.getRawMessage(),
                request.getTimestamp(),
                request.getTraceId(),
                request.getSpanId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("logId", logId));
    }
}
