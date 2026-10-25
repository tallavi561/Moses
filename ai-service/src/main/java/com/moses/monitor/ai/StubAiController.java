package com.moses.monitor.ai;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Honest placeholders: these return 501 rather than fabricated results,
 * since anomaly-detection rules and the summarization approach have not
 * been designed yet.
 */
@RestController
@RequestMapping("/api/ai")
public class StubAiController {

    @PostMapping("/anomaly-check")
    public ResponseEntity<Map<String, String>> anomalyCheck() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("status", "not_implemented", "reason", "Anomaly detection rules not yet designed."));
    }

    @PostMapping("/summarize")
    public ResponseEntity<Map<String, String>> summarize() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("status", "not_implemented", "reason", "Summarization approach not yet designed."));
    }
}
