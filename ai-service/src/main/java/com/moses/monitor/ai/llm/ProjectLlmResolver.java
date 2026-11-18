package com.moses.monitor.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which model and key answer for one environment of one project: its own setting if the owner
 * gave it one, else the project's default. Read from auth-service (which stores the key
 * encrypted) over its /internal endpoint, cached for a short while; clients are reused per
 * distinct setting.
 */
@Component
public class ProjectLlmResolver {

    private static final Logger log = LoggerFactory.getLogger(ProjectLlmResolver.class);
    private static final long TTL_MS = 30_000;
    private static final int MAX_CACHED = 5_000;

    private record Cached(LlmClient client, long expiresAtMs) { }

    private final RestTemplate http;
    private final String authServiceUrl;
    private final String internalToken;
    private final int timeoutSeconds;
    private final int agentTimeoutSeconds;
    private final ConcurrentHashMap<String, Cached> byScope = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LlmClient> bySetting = new ConcurrentHashMap<>();

    public ProjectLlmResolver(@Value("${moses.auth-service.url}") String authServiceUrl,
                              @Value("${moses.internal.token:}") String internalToken,
                              @Value("${moses.llm.timeout-seconds:20}") int timeoutSeconds,
                              @Value("${moses.llm.agent-timeout-seconds:180}") int agentTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.http = new RestTemplate(factory);
        this.authServiceUrl = authServiceUrl.endsWith("/") ? authServiceUrl.substring(0, authServiceUrl.length() - 1) : authServiceUrl;
        this.internalToken = internalToken;
        this.timeoutSeconds = timeoutSeconds;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
    }

    /** The environment's client, or a disabled one when nothing is configured (or it can't be read). */
    public LlmClient forScope(String projectId, String environment) {
        if (projectId == null || environment == null) {
            return new DisabledLlmClient();
        }
        String key = projectId + "|" + environment;
        long now = System.currentTimeMillis();
        Cached hit = byScope.get(key);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.client();
        }
        LlmClient client = fetch(projectId, environment);
        if (byScope.size() >= MAX_CACHED) {
            byScope.clear();
        }
        byScope.put(key, new Cached(client, now + TTL_MS));
        return client;
    }

    /** A client for settings someone is about to save (not cached). */
    public LlmClient forSettings(String provider, String model, String apiKey, String baseUrl) {
        return new LlmConfig().llmClient(provider, model, apiKey == null ? "" : apiKey,
                baseUrl == null ? "" : baseUrl, timeoutSeconds, agentTimeoutSeconds);
    }

    private LlmClient fetch(String projectId, String environment) {
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("INTERNAL_API_TOKEN is not set; project AI models can't be read");
            return new DisabledLlmClient();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            java.net.URI uri = UriComponentsBuilder.fromHttpUrl(authServiceUrl)
                    .pathSegment("internal", "projects", projectId, "llm")
                    .queryParam("environment", environment).build().encode().toUri();
            Map<?, ?> s = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (s == null) {
                return new DisabledLlmClient();
            }
            String provider = str(s.get("provider"));
            String model = str(s.get("model"));
            String apiKey = str(s.get("apiKey"));
            String baseUrl = str(s.get("baseUrl"));
            String fingerprint = sha256(provider + "\n" + model + "\n" + baseUrl + "\n" + apiKey);
            if (bySetting.size() >= MAX_CACHED) {
                bySetting.clear();
            }
            return bySetting.computeIfAbsent(fingerprint, f -> forSettings(provider, model, apiKey, baseUrl));
        } catch (HttpClientErrorException.NotFound e) {
            return new DisabledLlmClient();
        } catch (RuntimeException e) {
            log.warn("could not read the AI model setting of project {}: {}", projectId, e.getClass().getSimpleName());
            return new DisabledLlmClient();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
