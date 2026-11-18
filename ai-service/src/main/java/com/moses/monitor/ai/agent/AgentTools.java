package com.moses.monitor.ai.agent;

import com.moses.monitor.ai.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's tools, all read-only: the repository (when the project has one) and the project's
 * monitoring data. A {@link Session} binds them to one project and environment for one answer.
 */
@Component
public class AgentTools {

    private final RepoReader repos;
    private final MonitoringReader monitoring;

    public AgentTools(RepoReader repos, MonitoringReader monitoring) {
        this.repos = repos;
        this.monitoring = monitoring;
    }

    /** Opens the repository (cloning or refreshing it) up front, so a broken URL is reported once. */
    public Session session(MonitoringReader.Scope scope, String repoUrl) {
        RepoReader.Repo repo = null;
        String repoProblem = null;
        if (repoUrl != null) {
            try {
                repo = repos.open(repoUrl);
            } catch (Exception e) {
                repoProblem = e.getMessage();
            }
        }
        return new Session(scope, repo, repoProblem);
    }

    public final class Session implements LlmClient.ToolExecutor {

        private final MonitoringReader.Scope scope;
        private final RepoReader.Repo repo;
        private final String repoProblem;

        Session(MonitoringReader.Scope scope, RepoReader.Repo repo, String repoProblem) {
            this.scope = scope;
            this.repo = repo;
            this.repoProblem = repoProblem;
        }

        public String repoWebUrl() {
            return repo == null ? null : repo.webUrl();
        }

        public String branch() {
            return repo == null ? null : repo.branch();
        }

        public List<LlmClient.ToolSpec> specs() {
            List<LlmClient.ToolSpec> specs = new ArrayList<>();
            if (repo != null) {
                specs.add(spec("repo_search",
                        "Search the repository's files for text (case-insensitive). Returns path:line:text for each "
                                + "matching line. Best first step to find where something is implemented.",
                        props("query", str("Text to look for, e.g. a class, function, config key or error message"),
                                "path", str("Optional folder or file to search in, e.g. 'frontend/src'"),
                                "regex", Map.of("type", "boolean", "description", "Treat query as an extended regex (default false)")),
                        "query"));
                specs.add(spec("repo_read_file",
                        "Read a file from the repository with line numbers (up to 300 lines per call).",
                        props("path", str("Path from the repository root, e.g. 'api-gateway/pom.xml'"),
                                "start_line", integer("First line to read (default 1)"),
                                "end_line", integer("Last line to read")),
                        "path"));
                specs.add(spec("repo_list_files",
                        "List the repository's files, optionally only under one folder.",
                        props("path", str("Optional folder, e.g. 'auth-service/src'")),
                        null));
                specs.add(spec("repo_recent_commits",
                        "The latest commits (sha, date, author, subject), optionally only those touching one path.",
                        props("limit", integer("How many, 1-50 (default 15)"),
                                "path", str("Optional file or folder")),
                        null));
                specs.add(spec("repo_show_commit",
                        "One commit's message, changed files and diff.",
                        props("sha", str("Commit hash (short or full)")),
                        "sha"));
            }
            specs.add(spec("search_logs",
                    "Search this project's logs in the current environment, newest first. All given words must "
                            + "appear (AND). IDs, IPs and long numbers are not searchable as words - use service or trace_id.",
                    props("words", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Words that must all appear, e.g. ['timeout'] (optional)"),
                            "service", str("Only this service's logs (exact service name, optional)"),
                            "min_level", integer("Minimum level: 0 TRACE, 1 DEBUG, 2 INFO, 3 WARN, 4 ERROR, 5 FATAL"),
                            "minutes_back", integer("How far back to look, in minutes (default 60, max 20160 = 14 days)"),
                            "trace_id", str("Only logs of this trace"),
                            "limit", integer("Max logs to return (default 50, max 200)")),
                    null));
            specs.add(spec("get_trace",
                    "Every log of one request across services (one trace), oldest first.",
                    props("trace_id", str("The trace id")),
                    "trace_id"));
            specs.add(spec("get_services",
                    "The project's services in the current environment with status, last heartbeat, CPU/memory, "
                            + "health-probe results, and the connections between them.",
                    Map.of(),
                    null));
            specs.add(spec("get_metrics",
                    "CPU% and memory (MB) history for one or more services over a time window: sample count, "
                            + "avg/min/max for each metric, and when the latest sample was. Use this for \"how much "
                            + "CPU/memory is X using\" or trend questions instead of get_services, which only has "
                            + "the latest reading.",
                    props("services", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Service names to cover (optional - all services if left out)"),
                            "minutes_back", integer("How far back to look, in minutes (default 60, max 10080 = 7 days)")),
                    null));
            return specs;
        }

        @Override
        public String run(String name, Map<String, Object> in) throws Exception {
            if (name.startsWith("repo_") && repo == null) {
                return repoProblem == null ? "No repository is connected to this project."
                        : "The repository could not be opened: " + repoProblem;
            }
            return switch (name) {
                case "repo_search" -> repos.search(repo, s(in, "query"), s(in, "path"), Boolean.TRUE.equals(in.get("regex")));
                case "repo_read_file" -> repos.readFile(repo, required(in, "path"), i(in, "start_line"), i(in, "end_line"));
                case "repo_list_files" -> repos.listFiles(repo, s(in, "path"));
                case "repo_recent_commits" -> repos.recentCommits(repo, i(in, "limit") == null ? 15 : i(in, "limit"), s(in, "path"));
                case "repo_show_commit" -> repos.showCommit(repo, required(in, "sha"));
                case "search_logs" -> monitoring.searchLogs(scope, words(in.get("words")), s(in, "service"),
                        i(in, "min_level"), i(in, "minutes_back"), s(in, "trace_id"), i(in, "limit"));
                case "get_trace" -> monitoring.trace(scope, required(in, "trace_id"));
                case "get_services" -> monitoring.services(scope);
                case "get_metrics" -> monitoring.metricsHistory(scope, words(in.get("services")), i(in, "minutes_back"));
                default -> throw new IllegalArgumentException("unknown tool " + name);
            };
        }
    }

    // ---- schema helpers ----

    private static LlmClient.ToolSpec spec(String name, String description, Map<String, Object> properties, String required) {
        return new LlmClient.ToolSpec(name, description, properties, required == null ? List.of() : List.of(required));
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    // ---- input helpers (the model's JSON is parsed, never trusted to have the right shape) ----

    private static String s(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : v.toString();
    }

    private static String required(Map<String, Object> in, String key) {
        String v = s(in, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return v;
    }

    private static Integer i(Map<String, Object> in, String key) {
        Object v = in.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a number");
            }
        }
        return null;
    }

    private static List<String> words(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().filter(x -> x != null).map(Object::toString).toList();
        }
        if (v instanceof String str && !str.isBlank()) {
            return List.of(str.trim().split("\\s+"));
        }
        return List.of();
    }
}
