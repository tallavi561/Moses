package com.moses.monitor.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Polls every node that has a probeUrl set and derives its status from the HTTP response - the
 * "URL prober" piece of the architecture monitoring plan: a node the user hasn't wired a real
 * agent into yet can still report real UP/DOWN state just by giving it a URL to watch. Runs each
 * poll round on virtual threads so one slow/unreachable endpoint never blocks the others.
 */
@Component
public class EndpointProber {

    private static final Logger log = LoggerFactory.getLogger(EndpointProber.class);

    private final MongoTemplate mongoTemplate;
    private final Duration timeout;
    private final HttpClient httpClient;

    public EndpointProber(MongoTemplate mongoTemplate, @Value("${moses.prober.timeout-ms}") long timeoutMs) {
        this.mongoTemplate = mongoTemplate;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Scheduled(fixedDelayString = "${moses.prober.interval-ms}")
    public void probeAll() {
        List<ProcessState> targets = mongoTemplate.findAll(ProcessState.class).stream()
                .filter(s -> s.getProbeUrl() != null && !s.getProbeUrl().isBlank())
                .toList();
        if (targets.isEmpty()) {
            return;
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ProcessState target : targets) {
                executor.submit(() -> probeOne(target));
            }
            executor.shutdown();
            executor.awaitTermination(timeout.plusSeconds(5).toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void probeOne(ProcessState target) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(target.getProbeUrl()))
                    .timeout(timeout)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            recordResult(target, null, null, "Invalid URL: " + e.getMessage());
            return;
        }

        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            recordResult(target, response.statusCode(), latencyMs, null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("Probe failed for {} ({}): {}", target.getServiceName(), target.getProbeUrl(), e.toString());
            recordResult(target, null, latencyMs, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void recordResult(ProcessState target, Integer statusCode, Long latencyMs, String error) {
        ProcessStatus status = statusCode != null && statusCode >= 200 && statusCode < 400
                ? ProcessStatus.UP
                : ProcessStatus.DOWN;

        ProcessState current = mongoTemplate.findById(target.getServiceName(), ProcessState.class);
        if (current == null) {
            return;
        }
        current.setStatus(status);
        current.setLastSeen(Instant.now());
        current.setLastProbeAt(Instant.now());
        current.setLastProbeStatusCode(statusCode);
        current.setLastProbeLatencyMs(latencyMs);
        current.setLastProbeError(error);
        mongoTemplate.save(current);
    }
}
