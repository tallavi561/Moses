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

        ResponseEntity<byte[]> downstreamResponse = restTemplate.exchange(targetUrl, method, entity, byte[].class);

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

    private static final java.util.Set<String> HOP_BY_HOP_HEADERS = java.util.Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");
}
