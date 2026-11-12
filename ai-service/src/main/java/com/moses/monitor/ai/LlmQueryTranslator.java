package com.moses.monitor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moses.monitor.ai.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a free-text question into a structured search using the configured {@link LlmClient}.
 * The model only ever sees the question and the list of service names - never log content.
 * Everything it returns is validated and clamped; any failure (no provider, timeout, garbage
 * output) falls back to the rule-based {@link NaiveNlQueryTranslator}, reported in the result.
 */
@Component
@Primary
public class LlmQueryTranslator implements NlQueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryTranslator.class);

    static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
    static final Duration MAX_WINDOW = Duration.ofDays(14); // matches the default retention
    static final int MAX_WORDS = 8;
    static final int MAX_TRACE_ID_LENGTH = 128;

    private final LlmClient llm;
    private final NaiveNlQueryTranslator naive;
    private final ServiceCatalog catalog;
    private final ObjectMapper mapper;

    public LlmQueryTranslator(LlmClient llm, NaiveNlQueryTranslator naive, ServiceCatalog catalog, ObjectMapper mapper) {
        this.llm = llm;
        this.naive = naive;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public TranslatedQuery translate(String question) {
        if (!llm.isEnabled()) {
            return naive.translate(question).asFallback("No LLM configured");
        }
        try {
            Instant now = Instant.now();
            List<String> services = catalog.serviceNames();
            String reply = llm.complete(systemPrompt(now, services), question);
            return parse(reply, services, now);
        } catch (Exception e) {
            // Question text is deliberately not logged (it may contain sensitive details).
            log.warn("LLM translation failed with {} ({}); using rule-based fallback",
                    e.getClass().getSimpleName(), llm.describe());
            return naive.translate(question).asFallback("LLM unavailable: " + e.getClass().getSimpleName());
        }
    }

    static String systemPrompt(Instant now, List<String> services) {
        String serviceList = services.isEmpty() ? "(unknown)" : String.join(", ", services);
        return """
                You convert a question about application logs into a structured search. \
                Reply with ONE JSON object and nothing else (no prose, no code fences).

                Current time (UTC): %s
                Known services: %s

                JSON fields (all optional except from and to):
                - "words": array of lowercase keywords that must ALL appear in the log message \
                (AND). Use specific content words only (e.g. "timeout", "declined", "payment"). \
                Do not include filler words, level names (error, warn), service names, or time expressions.
                - "service": exactly one name from the known services list, or null.
                - "logLevel": integer, an exact level filter: 0 TRACE, 1 DEBUG, 2 INFO, 3 WARN, 4 ERROR, \
                5 FATAL; use it when the question asks for errors (4), warnings (3), etc.; otherwise null.
                - "traceId": only if the question contains a specific trace id, else null.
                - "from", "to": ISO-8601 UTC instants for the time window implied by the question \
                ("last 10 minutes", "yesterday", "today"). If no time is mentioned use the last hour. \
                "to" must not be in the future.

                Rules: UUIDs, IP addresses, timestamps and long numbers are never searchable as words - \
                put a trace id in "traceId" and leave other ids out. The question may be in any language; \
                output keywords in the language the logs are likely written in (usually English).
                Example: {"words":["timeout"],"service":"order-service","logLevel":4,\
                "traceId":null,"from":"2026-01-01T10:00:00Z","to":"2026-01-01T11:00:00Z"}
                """.formatted(now, serviceList);
    }

    TranslatedQuery parse(String reply, List<String> services, Instant now) throws Exception {
        String json = extractJsonObject(reply);
        JsonNode root = mapper.readTree(json);
        if (!root.isObject()) {
            throw new IllegalArgumentException("LLM reply is not a JSON object");
        }

        List<String> words = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode wordsNode = root.get("words");
        if (wordsNode != null && wordsNode.isArray()) {
            for (JsonNode w : wordsNode) {
                if (!w.isTextual()) continue;
                for (String part : w.asText().toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                    if (!part.isBlank() && seen.add(part) && words.size() < MAX_WORDS) {
                        words.add(part);
                    }
                }
            }
        }

        String service = matchService(text(root, "service"), services);

        Integer level = null;
        JsonNode levelNode = root.get("logLevel");
        if (levelNode != null && levelNode.isInt() && levelNode.asInt() >= 0 && levelNode.asInt() <= 5) {
            level = levelNode.asInt();
        }

        String traceId = text(root, "traceId");
        if (traceId != null && traceId.length() > MAX_TRACE_ID_LENGTH) {
            traceId = null;
        }

        Instant to = instant(root, "to");
        Instant from = instant(root, "from");
        if (to == null || to.isAfter(now)) {
            to = now;
        }
        if (from == null || !from.isBefore(to)) {
            from = to.minus(DEFAULT_WINDOW);
        }
        Instant earliest = to.minus(MAX_WINDOW);
        if (from.isBefore(earliest)) {
            from = earliest;
        }

        return new TranslatedQuery(words, from, to, level, service, traceId, "llm", null);
    }

    private static String extractJsonObject(String reply) {
        if (reply == null) {
            throw new IllegalArgumentException("Empty LLM reply");
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in LLM reply");
        }
        return reply.substring(start, end + 1);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    private static Instant instant(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Case-insensitive match against known services; unknown names are dropped, not trusted. */
    private static String matchService(String candidate, List<String> services) {
        if (candidate == null) {
            return null;
        }
        for (String known : services) {
            if (known.equalsIgnoreCase(candidate)) {
                return known;
            }
        }
        return null;
    }
}
