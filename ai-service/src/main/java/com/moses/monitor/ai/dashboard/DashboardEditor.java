package com.moses.monitor.ai.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a free-text instruction about one dashboard ("only memory for checkout, last 6 hours")
 * into a partial update of its chart settings. The model sees the instruction, the dashboard's
 * current settings and the environment's service names - never logs or metrics. Everything it
 * returns is checked here: unknown services are dropped (and reported), the range is snapped to
 * one the chart offers, and anything it can't express becomes a note to the user instead.
 */
public final class DashboardEditor {

    static final List<Integer> RANGES = List.of(15, 60, 360, 1440, 10080);
    static final Set<String> METRICS = Set.of("cpu", "memory", "errors");
    static final int MAX_TITLE = 120;
    static final int MAX_NOTE = 400;

    /** The current settings, as process-monitor returns them. */
    public record Current(String title, String scope, String serviceName, List<String> services, String metric,
                          int minutesBack) { }

    /**
     * What to change (only the keys present) plus a one-line summary for the user and, when part
     * of the request couldn't be done, a note saying so.
     */
    public record Edit(Map<String, Object> changes, String summary, String note) { }

    private DashboardEditor() {
    }

    public static String systemPrompt(Current current, List<String> services) {
        String serviceList = services.isEmpty() ? "(none registered)" : String.join(", ", services);
        String shown = current.services().isEmpty() ? "all services" : String.join(", ", current.services());
        return """
                You edit one monitoring dashboard from a free-text instruction. Reply with ONE JSON \
                object and nothing else (no prose, no code fences).

                The dashboard is a single line chart of one metric for a set of services over a time range.
                Current settings:
                - title: %s
                - services shown: %s
                - metric: %s
                - time range (minutes back): %d
                Services in this environment: %s

                JSON fields - include ONLY the ones the instruction changes:
                - "title": new title (max 120 characters).
                - "services": the full new list of services to show, names exactly from the list above; \
                [] means all services. "add X" means the current list plus X; "remove X" means without X \
                (if the current list is "all services", expand it to the full list first).
                - "metric": "cpu", "memory", or "errors" (ERROR/FATAL logs per minute).
                - "minutesBack": one of 15, 60, 360, 1440, 10080 (the chart offers only these; pick the closest).
                Always include:
                - "summary": one short sentence saying what you changed, in the language of the instruction.
                - "note": if part of the instruction cannot be done with these settings (e.g. another \
                chart type, alerts, a metric other than CPU/memory/errors, a service not in the list), say so \
                briefly in the language of the instruction; otherwise null.
                Example: {"services":["checkout"],"metric":"memory","minutesBack":360,\
                "summary":"Showing memory for checkout over the last 6 hours.","note":null}
                """.formatted(current.title(), shown, current.metric(), current.minutesBack(), serviceList);
    }

    public static Edit parse(String reply, Current current, List<String> known, ObjectMapper mapper) throws Exception {
        if (reply == null) {
            throw new IllegalArgumentException("Empty LLM reply");
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in LLM reply");
        }
        JsonNode root = mapper.readTree(reply.substring(start, end + 1));
        if (!root.isObject()) {
            throw new IllegalArgumentException("LLM reply is not a JSON object");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();

        String title = text(root, "title");
        if (title != null && !title.equals(current.title())) {
            changes.put("title", title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).trim() : title);
        }

        JsonNode servicesNode = root.get("services");
        if (servicesNode != null && servicesNode.isArray()) {
            Set<String> picked = new LinkedHashSet<>();
            for (JsonNode s : servicesNode) {
                if (!s.isTextual() || s.asText().isBlank()) continue;
                String match = match(s.asText().trim(), known);
                if (match != null) {
                    picked.add(match);
                } else {
                    dropped.add(s.asText().trim());
                }
            }
            // Every requested name unknown: keep what's shown rather than silently showing everything.
            boolean allUnknown = picked.isEmpty() && !dropped.isEmpty();
            List<String> next = new ArrayList<>(picked);
            if (!allUnknown && !next.equals(current.services())) {
                changes.put("services", next);
            }
        }

        String metric = text(root, "metric");
        if (metric != null) {
            metric = metric.toLowerCase();
            if (METRICS.contains(metric) && !metric.equals(current.metric())) {
                changes.put("metric", metric);
            }
        }

        JsonNode minutesNode = root.get("minutesBack");
        if (minutesNode != null && minutesNode.isNumber() && minutesNode.asDouble() > 0) {
            int snapped = snap(minutesNode.asDouble());
            if (snapped != current.minutesBack()) {
                changes.put("minutesBack", snapped);
            }
        }

        String summary = text(root, "summary");
        String note = text(root, "note");
        if (!dropped.isEmpty()) {
            String unknown = "Unknown service(s): " + String.join(", ", dropped);
            note = note == null ? unknown : note + " (" + unknown + ")";
        }
        if (note != null && note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE);
        }
        return new Edit(changes, summary, note);
    }

    /** The chart's range closest to what was asked, on a log scale (so "2h" is 1h, "4h" is 6h). */
    static int snap(double minutes) {
        int best = RANGES.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (int r : RANGES) {
            double d = Math.abs(Math.log(r) - Math.log(minutes));
            if (d < bestDistance) {
                bestDistance = d;
                best = r;
            }
        }
        return best;
    }

    private static String match(String candidate, List<String> known) {
        for (String k : known) {
            if (k.equalsIgnoreCase(candidate)) {
                return k;
            }
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }
}
