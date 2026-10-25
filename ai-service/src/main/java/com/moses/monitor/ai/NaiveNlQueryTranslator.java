package com.moses.monitor.ai;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PLACEHOLDER implementation. Good enough to prove the NL -> structured
 * query -> search-service pipeline end to end, but it is a rule-based
 * heuristic, not real language understanding:
 *   - drops a small English/Hebrew stop-word list
 *   - defaults to "last 1 hour" since it cannot parse time expressions
 *   - guesses log level only from a literal level-name match
 *
 * TODO: replace with an actual LLM call (e.g. the Claude API) that returns
 * structured {words, from, to, logLevel} - this class exists so the rest of
 * the pipeline (controller -> search-service -> response) can be built and
 * tested before that decision is made.
 */
@Component
public class NaiveNlQueryTranslator implements NlQueryTranslator {

    private static final Set<String> STOP_WORDS = Set.of(
            "show", "me", "find", "search", "for", "the", "a", "an", "in", "on", "at", "of",
            "all", "logs", "log", "last", "please",
            "תראה", "לי", "מצא", "חפש", "את", "ה", "כל", "לוגים", "לוג", "אחרונה", "אחרון");

    private static final Set<String> ERROR_WORDS = Set.of("error", "errors", "שגיאה", "שגיאות");
    private static final Set<String> WARN_WORDS = Set.of("warning", "warnings", "warn", "אזהרה");

    @Override
    public TranslatedQuery translate(String question) {
        String normalized = question.toLowerCase();
        List<String> words = Arrays.stream(normalized.split("\\s+"))
                .map(w -> w.replaceAll("[^\\p{L}\\p{N}._-]", ""))
                .filter(w -> !w.isBlank())
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toList());

        Integer logLevel = null;
        if (words.stream().anyMatch(ERROR_WORDS::contains)) {
            logLevel = 4; // matches LogLevel.ERROR in monitor-common
            words.removeIf(ERROR_WORDS::contains);
        } else if (words.stream().anyMatch(WARN_WORDS::contains)) {
            logLevel = 3; // matches LogLevel.WARN
            words.removeIf(WARN_WORDS::contains);
        }

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(1));

        return new TranslatedQuery(words, from, to, logLevel);
    }
}
