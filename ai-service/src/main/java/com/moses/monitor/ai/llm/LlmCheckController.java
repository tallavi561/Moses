package com.moses.monitor.ai.llm;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Tries a model and key an owner is about to save, for auth-service (which stores them). Under
 * /internal, which api-gateway never proxies, and guarded by INTERNAL_API_TOKEN as well.
 */
@RestController
public class LlmCheckController {

    public record CheckRequest(String provider, String model, String apiKey, String baseUrl) { }

    private final ProjectLlmResolver resolver;
    private final byte[] internalToken;

    public LlmCheckController(ProjectLlmResolver resolver, @Value("${moses.internal.token:}") String internalToken) {
        this.resolver = resolver;
        this.internalToken = internalToken == null || internalToken.isBlank() ? null : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/llm/check")
    public Map<String, Object> check(HttpServletRequest request, @RequestBody CheckRequest body) {
        String given = request.getHeader("X-Internal-Token");
        if (internalToken == null || given == null || !MessageDigest.isEqual(internalToken, given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal only");
        }
        LlmClient client = resolver.forSettings(body.provider(), body.model(), body.apiKey(), body.baseUrl());
        if (!client.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "incomplete settings");
        }
        try {
            client.verify();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "could not reach the provider");
        }
        return Map.of("ok", true, "model", client.describe());
    }
}
