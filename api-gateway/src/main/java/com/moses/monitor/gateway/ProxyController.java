package com.moses.monitor.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Enumeration;

/**
 * Generic reverse proxy: forwards every /api/** request to the backend
 * resolved by RouteConfig, relaying method, headers, query string and body
 * byte-for-byte, and relays the downstream status/headers/body back as-is.
 */
@RestController
public class ProxyController {

    private final RouteConfig routeConfig;
    private final RestTemplate restTemplate;

    public ProxyController(RouteConfig routeConfig, RestTemplate restTemplate) {
        this.routeConfig = routeConfig;
        this.restTemplate = restTemplate;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String backendBaseUrl = routeConfig.resolve(path);
        if (backendBaseUrl == null) {
            return ResponseEntity.notFound().build();
        }

        String queryString = request.getQueryString();
        String rawUrl = backendBaseUrl + path + (queryString != null ? "?" + queryString : "");
        // URI.create() (vs. RestTemplate's String-URL overload) treats the string as
        // already-encoded, so an incoming %3A isn't double-encoded into %253A - that
        // double-encoding otherwise corrupts ISO instants and breaks @RequestParam
        // Instant binding downstream.
        java.net.URI targetUrl = java.net.URI.create(rawUrl);

        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name.equalsIgnoreCase(HttpHeaders.HOST) || name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                continue;
            }
            headers.addAll(name, Collections.list(request.getHeaders(name)));
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        org.springframework.http.HttpEntity<byte[]> entity = new org.springframework.http.HttpEntity<>(body, headers);

        ResponseEntity<byte[]> downstreamResponse = exchangeWithColdStartRetry(targetUrl, method, entity);

        // Hop-by-hop headers must NOT be relayed: Transfer-Encoding/Content-Length in
        // particular conflict with the framing Spring MVC computes itself for a byte[]
        // body, which otherwise truncates the response the browser sees.
        HttpHeaders responseHeaders = new HttpHeaders();
        downstreamResponse.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                responseHeaders.addAll(name, values);
            }
        });

        return ResponseEntity.status(downstreamResponse.getStatusCode())
                .headers(responseHeaders)
                .body(downstreamResponse.getBody());
    }

    // Free-tier backends sleep when idle and take ~1 min to wake; Render's edge answers
    // 502/503/504 (before the request reaches the app, so a retry is safe even for POST)
    // or the connection is refused until then. Retry within a bounded window instead of
    // surfacing the 502 to the browser.
    private static final long COLD_START_WINDOW_MS = 75_000;
    private static final long RETRY_DELAY_MS = 4_000;

    private ResponseEntity<byte[]> exchangeWithColdStartRetry(
            java.net.URI targetUrl, HttpMethod method, org.springframework.http.HttpEntity<byte[]> entity) {
        long deadline = System.currentTimeMillis() + COLD_START_WINDOW_MS;
        while (true) {
            try {
                ResponseEntity<byte[]> response = restTemplate.exchange(targetUrl, method, entity, byte[].class);
                int status = response.getStatusCode().value();
                boolean waking = status == 502 || status == 503 || status == 504;
                if (!waking || System.currentTimeMillis() + RETRY_DELAY_MS > deadline) {
                    return response;
                }
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // A read timeout means the backend got the request, so don't replay it.
                boolean readTimeout = e.getCause() instanceof java.net.SocketTimeoutException
                        && !(e.getCause() instanceof java.net.http.HttpConnectTimeoutException)
                        && String.valueOf(e.getCause().getMessage()).toLowerCase().contains("read");
                if (readTimeout || System.currentTimeMillis() + RETRY_DELAY_MS > deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }
    }

    private static final java.util.Set<String> HOP_BY_HOP_HEADERS = java.util.Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");
}
