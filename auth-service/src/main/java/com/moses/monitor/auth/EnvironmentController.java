package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A project's environments, the API keys monitored services report in with, and the two lookups
 * api-gateway makes before it lets a monitoring request through: "may this user work in this
 * project and environment?" and "which project and environment does this key belong to?".
 */
@RestController
public class EnvironmentController {

    public record EnvironmentRequest(@NotBlank String name) { }

    public record CreateKeyRequest(@NotBlank String environment) { }

    public record ResolveKeyRequest(@NotBlank String key) { }

    /** {@code id} identifies the key for revoking; it is a hash and cannot be used as the key. */
    public record KeyView(String id, String environment, String prefix, Instant createdAt) {
        static KeyView of(ApiKey key) {
            return new KeyView(key.getKeyHash(), key.getEnvironment(), key.getPrefix(), key.getCreatedAt());
        }
    }

    /** The only response that ever carries the key itself. */
    public record CreatedKeyView(String id, String environment, String prefix, Instant createdAt, String key) { }

    /** {@code role} is null when the scope came from an API key instead of a signed-in user. */
    public record ScopeView(String projectId, String environment, Project.Role role) { }

    static final String KEY_PREFIX = "msk_";
    private static final int VISIBLE_PREFIX_LENGTH = KEY_PREFIX.length() + 6;
    private static final int MAX_ENVIRONMENTS = 20;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,29}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;
    private final CurrentUser currentUser;
    private final ProjectGuard guard;

    public EnvironmentController(MongoTemplate mongoTemplate, CurrentUser currentUser, ProjectGuard guard) {
        this.mongoTemplate = mongoTemplate;
        this.currentUser = currentUser;
        this.guard = guard;
    }

    // ---- environments ----

    @PostMapping("/api/projects/{projectId}/environments")
    public List<String> addEnvironment(HttpServletRequest request, @PathVariable String projectId,
                                       @Valid @RequestBody EnvironmentRequest body) {
        Project project = guard.requireOwner(projectId, currentUser.require(request));
        String name = normalizeEnvironment(body.name());

        List<String> environments = new java.util.ArrayList<>(project.getEnvironments());
        if (environments.contains(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "environment already exists");
        }
        if (environments.size() >= MAX_ENVIRONMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many environments");
        }
        environments.add(name);
        saveEnvironments(projectId, environments);
        return environments;
    }

    /**
     * Removes the environment, revokes its keys and forgets its own AI model setting. The logs and service state already recorded
     * under it are not deleted - they just stop being reachable (logs expire by retention).
     */
    @DeleteMapping("/api/projects/{projectId}/environments/{name}")
    public List<String> removeEnvironment(HttpServletRequest request, @PathVariable String projectId,
                                          @PathVariable String name) {
        Project project = guard.requireOwner(projectId, currentUser.require(request));

        List<String> environments = new java.util.ArrayList<>(project.getEnvironments());
        if (!environments.remove(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "environment not found");
        }
        if (environments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a project needs at least one environment");
        }
        saveEnvironments(projectId, environments);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(projectId)),
                new Update().unset("environment_llm." + name), Project.class);
        mongoTemplate.remove(
                new Query(Criteria.where("project_id").is(projectId).and("environment").is(name)), ApiKey.class);
        return environments;
    }

    // ---- API keys ----

    @GetMapping("/api/projects/{projectId}/keys")
    public List<KeyView> keys(HttpServletRequest request, @PathVariable String projectId) {
        guard.requireOwner(projectId, currentUser.require(request));
        Query query = new Query(Criteria.where("project_id").is(projectId))
                .with(Sort.by(Sort.Direction.ASC, "created_at"));
        return mongoTemplate.find(query, ApiKey.class).stream().map(KeyView::of).toList();
    }

    @PostMapping("/api/projects/{projectId}/keys")
    public CreatedKeyView createKey(HttpServletRequest request, @PathVariable String projectId,
                                    @Valid @RequestBody CreateKeyRequest body) {
        User user = currentUser.require(request);
        Project project = guard.requireOwner(projectId, user);
        if (!project.getEnvironments().contains(body.environment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "environment not found");
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyHash(ApiKey.hash(key));
        apiKey.setProjectId(projectId);
        apiKey.setEnvironment(body.environment());
        apiKey.setPrefix(key.substring(0, VISIBLE_PREFIX_LENGTH));
        apiKey.setCreatedBy(user.getId());
        apiKey.setCreatedAt(Instant.now());
        mongoTemplate.insert(apiKey);
        return new CreatedKeyView(apiKey.getKeyHash(), apiKey.getEnvironment(), apiKey.getPrefix(),
                apiKey.getCreatedAt(), key);
    }

    @DeleteMapping("/api/projects/{projectId}/keys/{keyId}")
    public List<KeyView> revokeKey(HttpServletRequest request, @PathVariable String projectId,
                                   @PathVariable String keyId) {
        guard.requireOwner(projectId, currentUser.require(request));
        mongoTemplate.remove(new Query(Criteria.where("_id").is(keyId).and("project_id").is(projectId)), ApiKey.class);
        return keys(request, projectId);
    }

    // ---- lookups made by api-gateway ----

    /** The signed-in user's role in the project, provided the environment exists in it. */
    @GetMapping("/api/projects/{projectId}/access")
    public ScopeView access(HttpServletRequest request, @PathVariable String projectId,
                            @RequestParam String environment) {
        User user = currentUser.require(request);
        Project project = guard.requireMembership(projectId, user);
        if (!project.getEnvironments().contains(environment)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "environment not found");
        }
        return new ScopeView(projectId, environment, project.roleOf(user.getId()));
    }

    /**
     * Which project and environment an API key reports into. api-gateway calls this directly;
     * the path is deliberately not one the gateway proxies.
     */
    @PostMapping("/api/keys/resolve")
    public ScopeView resolveKey(@Valid @RequestBody ResolveKeyRequest body) {
        ApiKey apiKey = mongoTemplate.findById(ApiKey.hash(body.key()), ApiKey.class);
        if (apiKey == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown key");
        }
        return new ScopeView(apiKey.getProjectId(), apiKey.getEnvironment(), null);
    }

    private void saveEnvironments(String projectId, List<String> environments) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(projectId)),
                new Update().set("environments", environments), Project.class);
    }

    static String normalizeEnvironment(String raw) {
        String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "environment name: lowercase letters, digits and dashes, up to 30 characters");
        }
        return name;
    }
}
