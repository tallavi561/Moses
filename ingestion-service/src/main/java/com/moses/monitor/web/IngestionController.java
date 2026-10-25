package com.moses.monitor.web;

import com.moses.monitor.ingestion.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody IncomingLogRequest request) {
        String logId = ingestionService.ingest(
                request.getServiceName(),
                request.getLogLevel(),
                request.getIp(),
                request.getRawMessage(),
                request.getTimestamp());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("logId", logId));
    }
}
