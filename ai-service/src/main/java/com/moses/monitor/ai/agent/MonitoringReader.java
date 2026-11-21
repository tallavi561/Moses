package com.moses.monitor.ai.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The agent's read-only view of a project's monitoring data, over the same REST APIs the UI
 * uses. Every call carries the project and environment of the conversation, so the agent can
 * never see another project's logs or services.
 */
@Component
public class MonitoringReader {

    /** Which project and environment the agent is answering about. */
    public record Scope(String projectId, String environment) { }

    private static final String[] LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"};
    private static final long MAX_MINUTES_BACK = Duration.ofDays(14).toMinutes();
    private static final int MAX_MESSAGE_CHARS = 500;

    private final RestTemplate http;
    private final String searchUrl;
    private final String processUrl;

    public MonitoringReader(@Value("${moses.search-service.url}") String searchUrl,
                            @Value("${moses.process-monitor.url}") String processUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.http = new RestTemplate(factory);
        this.searchUrl = trim(searchUrl);
        this.processUrl = trim(processUrl);
    }

    public String searchLogs(Scope scope, List<String> words, String service, Integer minLevel,
                             Integer minutesBack, String traceId, Integer limit) {
        long minutes = Math.max(1, Math.min(minutesBack == null ? 60 : minutesBack, MAX_MINUTES_BACK));
        int size = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        Instant to = Instant.now();
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(searchUrl + "/api/search/page")
                .queryParam("from", to.minus(Duration.ofMinutes(minutes)))
                .queryParam("to", to)
                .queryParam("limit", size);
        if (words != null) {
            words.stream().filter(w -> w != null && !w.isBlank()).limit(8).forEach(w -> uri.queryParam("words", w.trim()));
        }
        if (service != null && !service.isBlank()) {
            uri.queryParam("service", service.trim());
        }
        if (minLevel != null) {
            uri.queryParam("logLevel", Math.max(0, Math.min(5, minLevel)));
        }
        if (traceId != null && !traceId.isBlank()) {
            uri.queryParam("traceId", traceId.trim());
        }
        Map<?, ?> page = get(scope, uri.build().encode().toUri(), Map.class);
        List<?> logs = page == null || !(page.get("logs") instanceof List<?> l) ? List.of() : l;
        boolean more = page != null && Boolean.TRUE.equals(page.get("hasMore"));
        if (logs.isEmpty()) {
            return "No logs matched in the last " + minutes + " minutes (environment " + scope.environment() + ").";
        }
        StringBuilder out = new StringBuilder(logs.size() + " logs, newest first"
                + (more ? " (more exist - narrow the search)" : "") + ":\n");
        for (Object o : logs) {
            out.append(formatLog((Map<?, ?>) o)).append('\n');
        }
        return out.toString();
    }

    public String trace(Scope scope, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("trace_id is required");
        }
        java.net.URI uri = UriComponentsBuilder.fromHttpUrl(searchUrl)
                .pathSegment("api", "search", "trace", traceId.trim()).build().encode().toUri();
        List<?> logs = get(scope, uri, List.class);
        if (logs == null || logs.isEmpty()) {
            return "No logs for trace " + traceId + ".";
        }
        StringBuilder out = new StringBuilder(logs.size() + " logs in trace " + traceId + ", oldest first:\n");
        for (Object o : logs) {
            out.append(formatLog((Map<?, ?>) o)).append('\n');
        }
        return out.toString();
    }

    /** Avg/min/max CPU% and memory over the window, not the raw points - a model doesn't need every sample. */
    public String metricsHistory(Scope scope, List<String> serviceNames, Integer minutesBack) {
        int minutes = Math.max(1, Math.min(minutesBack == null ? 60 : minutesBack, (int) Duration.ofDays(7).toMinutes()));
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(processUrl + "/api/process/metrics")
                .queryParam("minutesBack", minutes);
        if (serviceNames != null) {
            serviceNames.stream().filter(s -> s != null && !s.isBlank()).limit(20).forEach(s -> uri.queryParam("service", s.trim()));
        }
        List<?> series = get(scope, uri.build().encode().toUri(), List.class);
        if (series == null || series.isEmpty()) {
            return "No services with metrics in the last " + minutes + " minutes (environment " + scope.environment() + ").";
        }
        StringBuilder out = new StringBuilder("CPU%/memory over the last " + minutes + " minutes:\n");
        for (Object o : series) {
            Map<?, ?> s = (Map<?, ?>) o;
            Object name = s.get("serviceName");
            List<?> samples = s.get("samples") instanceof List<?> l ? l : List.of();
            if (samples.isEmpty()) {
                out.append("- ").append(name).append(": no metric samples in this window\n");
                continue;
            }
            out.append("- ").append(name).append(": ").append(samples.size()).append(" samples");
            summarize(out, samples, "cpuPercent", " | cpu% ");
            summarize(out, samples, "memoryMb", " | memMB ");
            Map<?, ?> last = (Map<?, ?>) samples.get(samples.size() - 1);
            out.append(" | latest at ").append(last.get("timestamp"));
            out.append('\n');
        }
        return out.toString();
    }

    private static void summarize(StringBuilder out, List<?> samples, String field, String label) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        int count = 0;
        for (Object o : samples) {
            Object v = ((Map<?, ?>) o).get(field);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                min = Math.min(min, d);
                max = Math.max(max, d);
                sum += d;
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        out.append(label).append(String.format("avg %.1f, min %.1f, max %.1f", sum / count, min, max));
    }

    public String services(Scope scope) {
        List<?> states = get(scope, java.net.URI.create(processUrl + "/api/process/state"), List.class);
        List<?> connections = get(scope, java.net.URI.create(processUrl + "/api/process/connections"), List.class);
        StringBuilder out = new StringBuilder("Services in environment " + scope.environment() + ":\n");
        if (states == null || states.isEmpty()) {
            out.append("(none registered)\n");
        } else {
            for (Object o : states) {
                Map<?, ?> s = (Map<?, ?>) o;
                out.append("- ").append(s.get("serviceName")).append(": ").append(s.get("status"));
                append(out, " | last seen ", s.get("lastSeen"));
                append(out, " | cpu% ", s.get("cpuPercent"));
                append(out, " | memMB ", s.get("memoryMb"));
                append(out, " | probe ", s.get("probeUrl"));
                append(out, " -> HTTP ", s.get("lastProbeStatusCode"));
                append(out, " in ms ", s.get("lastProbeLatencyMs"));
                append(out, " | probe error ", s.get("lastProbeError"));
                append(out, " | ", s.get("description"));
                out.append('\n');
            }
        }
        out.append("Connections:\n");
        if (connections == null || connections.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (Object o : connections) {
                Map<?, ?> c = (Map<?, ?>) o;
                out.append("- ").append(c.get("sourceService")).append(" -> ").append(c.get("targetService"))
                        .append(" (").append(c.get("direction")).append(')');
                append(out, " ", c.get("communicationType"));
                append(out, ": ", c.get("description"));
                out.append('\n');
            }
        }
        return out.toString();
    }

    // ---- incidents (process-monitor) ----

    /** One incident as process-monitor returns it (null fields included). */
    public Map<String, Object> incident(Scope scope, String id) {
        java.net.URI uri = UriComponentsBuilder.fromHttpUrl(processUrl).pathSegment("api", "process", "incidents", id)
                .build().encode().toUri();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = get(scope, uri, Map.class);
        return body;
    }

    public List<Map<String, Object>> similarIncidents(Scope scope, String id) {
        java.net.URI uri = UriComponentsBuilder.fromHttpUrl(processUrl)
                .pathSegment("api", "process", "incidents", id, "similar").queryParam("limit", 5).build().encode().toUri();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = get(scope, uri, List.class);
        return body == null ? List.of() : body;
    }

    /** Writes an AI investigation onto the incident, as the user who asked for it. */
    public void saveInvestigation(Scope scope, String userId, String id, Map<String, Object> investigation) {
        java.net.URI uri = UriComponentsBuilder.fromHttpUrl(processUrl)
                .pathSegment("api", "process", "incidents", id, "investigation").build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Moses-Project-Id", scope.projectId());
        headers.set("X-Moses-Environment", scope.environment());
        if (userId != null) {
            headers.set("X-Moses-User-Id", userId);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        http.exchange(uri, HttpMethod.PUT, new HttpEntity<>(investigation, headers), Map.class);
    }

    /** The incident library as text for the agent: past incidents, what broke, what the team concluded. */
    public String searchIncidentLibrary(Scope scope, String query, String status) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(processUrl + "/api/process/incidents/library")
                .queryParam("limit", 15);
        if (query != null && !query.isBlank()) b.queryParam("q", query.trim());
        if (status != null && !status.isBlank()) b.queryParam("status", status.trim().toUpperCase(java.util.Locale.ROOT));
        List<?> found = get(scope, b.build().encode().toUri(), List.class);
        if (found == null || found.isEmpty()) {
            return "No past incidents match" + (query == null || query.isBlank() ? "." : " \"" + query + "\".");
        }
        StringBuilder out = new StringBuilder(found.size() + " incidents, newest first:\n");
        for (Object o : found) {
            out.append(describeIncident((Map<?, ?>) o)).append("\n");
        }
        return out.toString();
    }

    /** A compact, model-friendly description of one incident. */
    public static String describeIncident(Map<?, ?> i) {
        StringBuilder s = new StringBuilder();
        s.append("- [").append(i.get("id")).append("] ").append(i.get("kind")).append(" in ").append(i.get("serviceName"))
                .append(" (env ").append(i.get("environment")).append("), opened ").append(i.get("openedAt"))
                .append(", ").append(i.get("state"));
        if (i.get("closedAt") != null) s.append(" at ").append(i.get("closedAt"));
        if ("ERROR_SPIKE".equals(i.get("kind"))) {
            s.append("; peak ").append(i.get("peakErrors")).append(" errors per ").append(i.get("windowMinutes"))
                    .append(" min, normally ~").append(Math.round(((Number) i.get("baselineErrors")).doubleValue()));
        }
        if ("FLOW_BROKEN".equals(i.get("kind"))) {
            s.append("; flow ").append(i.get("flowEntry")).append(" -> ").append(i.get("serviceName"))
                    .append(" within ").append(i.get("flowMaxSeconds")).append("s, ").append(i.get("brokenTraces"))
                    .append(" of ").append(i.get("checkedTraces")).append(" requests broke");
        }
        if (i.get("topErrors") instanceof List<?> errs && !errs.isEmpty()) {
            s.append("\n  errors: ");
            for (Object e : errs) {
                Map<?, ?> g = (Map<?, ?>) e;
                s.append("x").append(g.get("count")).append(" \"").append(truncate(String.valueOf(g.get("message")), 200)).append("\"; ");
            }
        }
        if (i.get("brokenExamples") instanceof List<?> br && !br.isEmpty()) {
            s.append("\n  broken requests: ");
            for (Object e : br) {
                Map<?, ?> b = (Map<?, ?>) e;
                s.append(b.get("reason")).append(" at ").append(b.get("stoppedAt")).append(" (trace ").append(b.get("traceId")).append("); ");
            }
        }
        Object review = i.get("reviewStatus");
        if (review != null && !"UNREVIEWED".equals(review)) {
            s.append("\n  team review: ").append(review);
            if (i.get("rootCause") != null) s.append("; root cause: ").append(truncate(String.valueOf(i.get("rootCause")), 400));
            if (i.get("resolution") != null) s.append("; fix: ").append(truncate(String.valueOf(i.get("resolution")), 400));
        }
        if (i.get("aiInvestigation") instanceof Map<?, ?> ai && "DONE".equals(ai.get("status"))) {
            s.append("\n  earlier AI investigation (unconfirmed): ").append(truncate(String.valueOf(ai.get("rootCause")), 300));
        }
        return s.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private <T> T get(Scope scope, java.net.URI uri, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Moses-Project-Id", scope.projectId());
        headers.set("X-Moses-Environment", scope.environment());
        return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), type).getBody();
    }

    private static String formatLog(Map<?, ?> log) {
        Object levelCode = log.get("logLevel");
        String level = levelCode instanceof Number n && n.intValue() >= 0 && n.intValue() < LEVELS.length
                ? LEVELS[n.intValue()] : String.valueOf(levelCode);
        String message = String.valueOf(log.get("rawMessage"));
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS) + "...";
        }
        StringBuilder line = new StringBuilder();
        line.append(log.get("timestamp")).append(' ').append(level).append(' ').append(log.get("serviceName"));
        if (log.get("traceId") != null) {
            line.append(" trace=").append(log.get("traceId"));
        }
        return line.append(" | ").append(message.replace('\n', ' ')).toString();
    }

    private static void append(StringBuilder out, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.append(label).append(value);
        }
    }

    private static String trim(String url) {
        if (url == null) {
            return "";
        }
        String u = url.startsWith("http://") || url.startsWith("https://") ? url : "http://" + url;
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
