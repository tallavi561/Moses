package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The AI model a project's agent uses: a default for the whole project, and optionally a
 * different one for a single environment (its own key, so its own bill). Only the owner changes
 * it; members see which model is set and the key's last 4 characters, never the key.
 *
 * A new key is checked with the provider (through ai-service) before it is saved, so a typo is
 * caught here rather than in the chat.
 */
@RestController
public class LlmSettingsController {

    public record LlmRequest(@NotBlank String provider,
                             @NotBlank @Size(max = 100) String model,
                             @Size(max = 300) String apiKey,
                             @Size(max = 200) String baseUrl) { }

    /** What a member sees: never the key itself. */
    public record LlmView(String provider, String model, String baseUrl, String keyHint, Instant updatedAt) {
        static LlmView of(LlmSettings s) {
            return s == null ? null : new LlmView(s.getProvider(), s.getModel(), s.getBaseUrl(), s.getKeyHint(),
                    s.getUpdatedAt());
        }
    }

    /** The project default and every environment's override (null = uses the project default). */
    public record LlmOverview(LlmView project, Map<String, LlmView> environments, boolean canStoreKeys) { }

    /** For ai-service only: the decrypted settings that apply to one environment. */
    public record ResolvedLlm(String source, String provider, String model, String baseUrl, String apiKey) { }

    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:/@-]{1,100}$");
    private static final Pattern HTTPS_URL = Pattern.compile("^https?://[^\\s]{3,190}$");

    private final MongoTemplate mongoTemplate;
    private final CurrentUser currentUser;
    private final ProjectGuard guard;
    private final SecretBox secrets;
    private final InternalAuth internal;
    private final RestTemplate http;
    private final String aiServiceUrl;

    public LlmSettingsController(MongoTemplate mongoTemplate, CurrentUser currentUser, ProjectGuard guard,
                                 SecretBox secrets, InternalAuth internal,
                                 @Value("${moses.ai-service.url}") String aiServiceUrl) {
        this.mongoTemplate = mongoTemplate;
        this.currentUser = currentUser;
        this.guard = guard;
        this.secrets = secrets;
        this.internal = internal;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.http = new RestTemplate(factory);
        this.aiServiceUrl = aiServiceUrl.endsWith("/") ? aiServiceUrl.substring(0, aiServiceUrl.length() - 1) : aiServiceUrl;
    }

    @GetMapping("/api/projects/{projectId}/llm")
    public LlmOverview overview(HttpServletRequest request, @PathVariable String projectId) {
        Project project = guard.requireMembership(projectId, currentUser.require(request));
        return overviewOf(project);
    }

    @PutMapping("/api/projects/{projectId}/llm")
    public LlmOverview setProjectDefault(HttpServletRequest request, @PathVariable String projectId,
                                         @Valid @RequestBody LlmRequest body) {
        User user = currentUser.require(request);
        Project project = guard.requireOwner(projectId, user);
        LlmSettings saved = prepare(body, project.getLlm(), user);
        mongoTemplate.updateFirst(byId(projectId), new Update().set("llm", saved), Project.class);
        project.setLlm(saved);
        return overviewOf(project);
    }

    @DeleteMapping("/api/projects/{projectId}/llm")
    public LlmOverview clearProjectDefault(HttpServletRequest request, @PathVariable String projectId) {
        Project project = guard.requireOwner(projectId, currentUser.require(request));
        mongoTemplate.updateFirst(byId(projectId), new Update().unset("llm"), Project.class);
        project.setLlm(null);
        return overviewOf(project);
    }

    @PutMapping("/api/projects/{projectId}/environments/{environment}/llm")
    public LlmOverview setEnvironmentOverride(HttpServletRequest request, @PathVariable String projectId,
                                              @PathVariable String environment, @Valid @RequestBody LlmRequest body) {
        User user = currentUser.require(request);
        Project project = guard.requireOwner(projectId, user);
        requireEnvironment(project, environment);
        LlmSettings saved = prepare(body, project.getEnvironmentLlm().get(environment), user);
        mongoTemplate.updateFirst(byId(projectId), new Update().set("environment_llm." + environment, saved), Project.class);
        project.getEnvironmentLlm().put(environment, saved);
        return overviewOf(project);
    }

    @DeleteMapping("/api/projects/{projectId}/environments/{environment}/llm")
    public LlmOverview clearEnvironmentOverride(HttpServletRequest request, @PathVariable String projectId,
                                                @PathVariable String environment) {
        Project project = guard.requireOwner(projectId, currentUser.require(request));
        requireEnvironment(project, environment);
        mongoTemplate.updateFirst(byId(projectId), new Update().unset("environment_llm." + environment), Project.class);
        project.getEnvironmentLlm().remove(environment);
        return overviewOf(project);
    }

    /** Called by ai-service for every agent answer (cached there briefly). */
    @GetMapping("/internal/projects/{projectId}/llm")
    public ResolvedLlm resolve(HttpServletRequest request, @PathVariable String projectId,
                               @RequestParam String environment) {
        internal.require(request);
        Project project = mongoTemplate.findById(projectId, Project.class);
        if (project == null || !project.getEnvironments().contains(environment)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project or environment not found");
        }
        LlmSettings own = project.getEnvironmentLlm().get(environment);
        LlmSettings chosen = own != null ? own : project.getLlm();
        if (chosen == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no AI model configured");
        }
        return new ResolvedLlm(own != null ? "environment" : "project", chosen.getProvider(), chosen.getModel(),
                chosen.getBaseUrl(), secrets.decrypt(chosen.getKeyCiphertext()));
    }

    /** The project's name and repository, for agent work not started from the chat (incident investigations). */
    @GetMapping("/internal/projects/{projectId}/info")
    public java.util.Map<String, String> info(HttpServletRequest request, @PathVariable String projectId) {
        internal.require(request);
        Project project = mongoTemplate.findById(projectId, Project.class);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
        }
        java.util.Map<String, String> info = new java.util.HashMap<>();
        info.put("name", project.getName());
        info.put("repoUrl", project.getRepoUrl());
        return info;
    }

    // ---- helpers ----

    /**
     * Validates the request, checks the key with the provider, and returns what to store. An
     * empty apiKey keeps the existing key (to change only the model) - but only when there is one
     * and the provider stays the same.
     */
    private LlmSettings prepare(LlmRequest body, LlmSettings existing, User user) {
        if (!secrets.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "the server cannot store keys yet (SECRETS_ENCRYPTION_KEY is not set)");
        }
        String provider = body.provider().trim().toLowerCase(java.util.Locale.ROOT);
        if (!provider.equals(LlmSettings.ANTHROPIC) && !provider.equals(LlmSettings.OPENAI_COMPATIBLE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider must be anthropic or openai-compatible");
        }
        String model = body.model().trim();
        if (!MODEL.matcher(model).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid model name");
        }
        String baseUrl = body.baseUrl() == null || body.baseUrl().isBlank() ? null : body.baseUrl().trim();
        if (provider.equals(LlmSettings.OPENAI_COMPATIBLE) && (baseUrl == null || !HTTPS_URL.matcher(baseUrl).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an OpenAI-compatible provider needs its API address");
        }
        if (provider.equals(LlmSettings.ANTHROPIC)) {
            baseUrl = null;
        }

        String apiKey = body.apiKey() == null ? "" : body.apiKey().trim();
        if (apiKey.isEmpty()) {
            if (existing == null || !provider.equals(existing.getProvider())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an API key is required");
            }
            apiKey = secrets.decrypt(existing.getKeyCiphertext());
        }

        check(provider, model, baseUrl, apiKey);

        LlmSettings settings = new LlmSettings();
        settings.setProvider(provider);
        settings.setModel(model);
        settings.setBaseUrl(baseUrl);
        settings.setKeyCiphertext(secrets.encrypt(apiKey));
        settings.setKeyHint(apiKey.length() <= 4 ? "****" : apiKey.substring(apiKey.length() - 4));
        settings.setUpdatedAt(Instant.now());
        settings.setUpdatedBy(user.getId());
        return settings;
    }

    /** Asks ai-service to try the key and model against the provider. */
    private void check(String provider, String model, String baseUrl, String apiKey) {
        if (internal.headerValue() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_API_TOKEN is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(InternalAuth.HEADER, internal.headerValue());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("model", model);
        body.put("baseUrl", baseUrl);
        body.put("apiKey", apiKey);
        try {
            http.postForEntity(aiServiceUrl + "/internal/llm/check", new HttpEntity<>(body, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 422) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, messageOf(e));
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "could not check the key right now");
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "could not check the key (is ai-service running?)");
        }
    }

    private static String messageOf(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        int at = body.indexOf("\"message\":\"");
        if (at >= 0) {
            int end = body.indexOf('"', at + 11);
            if (end > at) {
                return body.substring(at + 11, end);
            }
        }
        return "the provider rejected this key or model";
    }

    private LlmOverview overviewOf(Project project) {
        Map<String, LlmView> environments = new LinkedHashMap<>();
        for (String env : project.getEnvironments()) {
            environments.put(env, LlmView.of(project.getEnvironmentLlm().get(env)));
        }
        return new LlmOverview(LlmView.of(project.getLlm()), environments, secrets.isConfigured());
    }

    private static void requireEnvironment(Project project, String environment) {
        if (!project.getEnvironments().contains(environment)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "environment not found");
        }
    }

    private static Query byId(String projectId) {
        return new Query(Criteria.where("_id").is(projectId));
    }
}
