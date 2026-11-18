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
