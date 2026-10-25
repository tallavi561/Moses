package com.moses.monitor.tokenize;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Breaks a raw log message into index tokens.
 *
 * Rules (per project_specification.md open points, resolved):
 *  - Full lowercase, no stop-word filtering.
 *  - High-cardinality substrings (IPs, UUIDs, ISO timestamps, long numeric ids)
 *    are replaced with a generic placeholder BEFORE splitting, so the raw
 *    high-cardinality value never becomes a unique index token. The original
 *    value is still fully searchable via the raw_message field on the
 *    Log Store collection.
 *  - Qualified names such as "java.lang.NullPointerException" are kept as a
 *    single token (split only on whitespace, then trim outer punctuation).
 */
@Component
public class Tokenizer {

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{7,}\\b");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern OUTER_PUNCTUATION = Pattern.compile("^[^\\w<>]+|[^\\w<>]+$");

    public List<String> tokenize(String rawMessage) {
        List<String> tokens = new ArrayList<>();
        if (rawMessage == null || rawMessage.isBlank()) {
            return tokens;
        }

        String normalized = maskHighCardinality(rawMessage);

        for (String piece : WHITESPACE.split(normalized)) {
            String trimmed = OUTER_PUNCTUATION.matcher(piece).replaceAll("");
            if (trimmed.isEmpty()) {
                continue;
            }
            tokens.add(trimmed.toLowerCase());
        }
        return tokens;
    }

    private String maskHighCardinality(String message) {
        String result = ISO_TIMESTAMP.matcher(message).replaceAll("<timestamp>");
        result = UUID.matcher(result).replaceAll("<uuid>");
        result = IPV4.matcher(result).replaceAll("<ip>");
        result = LONG_NUMBER.matcher(result).replaceAll("<num>");
        return result;
    }
}
