package com.moses.monitor.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Read-only access to a project's GitHub repository through a local bare clone, refreshed with
 * {@code git fetch} at most once a minute. Every read is a plain git command run without a
 * shell; nothing here can change the repository or reach anything but the clone.
 *
 * Only https://github.com URLs are accepted, so a project setting can never point the agent at
 * a folder on this machine. And git runs anonymously (see {@link #isolate}): any user can type a
 * repository URL, so it must never be fetched with the credentials of the machine MoSes runs on -
 * that is what makes "public repositories only" true.
 */
@Component
public class RepoReader {

    private static final Logger log = LoggerFactory.getLogger(RepoReader.class);

    static final Pattern GITHUB_REPO =
            Pattern.compile("^https://github\\.com/([A-Za-z0-9_][A-Za-z0-9_.-]*)/([A-Za-z0-9_][A-Za-z0-9_.-]*?)(\\.git)?/?$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{4,40}$");

    private static final long FETCH_EVERY_MS = 60_000;
    private static final int OUTPUT_LIMIT_BYTES = 400_000;
    private static final int MAX_FILES_LISTED = 400;
    private static final int MAX_LINES_READ = 300;
    private static final int MAX_MATCHES = 80;
    private static final int MAX_LINE_CHARS = 300;
    private static final int MAX_DIFF_CHARS = 20_000;

    /**
     * All that git inherits from this process's environment. Everything else is dropped: tokens,
     * askpass programs, GIT_* overrides and MoSes's own secrets.
     */
    private static final Set<String> KEPT_ENV = Set.of("PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC",
            "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY",
            "SSL_CERT_FILE", "SSL_CERT_DIR");
    /**
     * The only settings taken from the machine's git config: how to trust HTTPS. Git for Windows
     * keeps its CA bundle there, so ignoring that config entirely breaks public clones too.
     */
    private static final List<String> TLS_SETTINGS = List.of("http.sslBackend", "http.sslCAInfo", "http.sslCAPath");

    /** A repository ready to read: where its clone is and how to link to it on GitHub. */
    public record Repo(Path dir, String webUrl, String branch) { }

    private final Path cacheRoot;
    private final Path noHome;
    private volatile List<String> tlsArgs;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFetch = new ConcurrentHashMap<>();

    public RepoReader(@Value("${moses.agent.repo-cache-dir:}") String cacheDir) {
        Path base = (cacheDir == null || cacheDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".moses", "repos")
                : Path.of(cacheDir)).toAbsolutePath();
        // A folder of its own: clones made before 1.3.2 may hold private repositories fetched with
        // the host's credentials, and must never be served again.
        this.cacheRoot = base.resolve("anonymous");
        // Stands in for the home directory and is never created: no .gitconfig, .netrc or cookies.
        this.noHome = base.resolve("no-home");
    }

    public static boolean isAcceptedUrl(String url) {
        return url != null && GITHUB_REPO.matcher(url.trim()).matches();
    }

    /** Clones on first use, then fetches when the copy is older than a minute. */
    public Repo open(String repoUrl) throws IOException {
        if (!isAcceptedUrl(repoUrl)) {
            throw new IllegalArgumentException("only https://github.com/<owner>/<repo> repositories are supported");
        }
        var m = GITHUB_REPO.matcher(repoUrl.trim());
        m.matches();
        String webUrl = "https://github.com/" + m.group(1) + "/" + m.group(2);
        Path dir = cacheRoot.resolve(shortHash(webUrl.toLowerCase()));

        synchronized (locks.computeIfAbsent(webUrl, k -> new Object())) {
            if (!Files.isDirectory(dir.resolve("refs"))) {
                Files.createDirectories(cacheRoot);
                Result clone = git(null, 180, "clone", "--bare", "--quiet", webUrl + ".git", dir.toString());
                if (clone.exit() != 0) {
                    throw new IOException("could not clone " + webUrl + " (is it public, and is git installed?)");
                }
                lastFetch.put(webUrl, System.currentTimeMillis());
            } else if (System.currentTimeMillis() - lastFetch.getOrDefault(webUrl, 0L) > FETCH_EVERY_MS) {
                Result fetch = git(dir, 60, "fetch", "--quiet", "--prune", "origin", "+refs/heads/*:refs/heads/*");
                if (fetch.exit() != 0) {
                    log.warn("git fetch failed for {}; reading the copy from the last fetch", webUrl);
                }
                lastFetch.put(webUrl, System.currentTimeMillis());
            }
        }
        Result head = git(dir, 10, "symbolic-ref", "--short", "HEAD");
        String branch = head.exit() == 0 && !head.out().isBlank() ? head.out().trim() : "main";
        return new Repo(dir, webUrl, branch);
    }

    public String listFiles(Repo repo, String path) throws IOException {
        List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "--name-only", "HEAD"));
        if (path != null && !path.isBlank()) {
            args.add("--");
            args.add(safePath(path));
        }
        Result r = git(repo.dir(), 30, args.toArray(String[]::new));
        requireOk(r, "list files");
        List<String> files = r.out().lines().toList();
        StringBuilder out = new StringBuilder(files.size() + " files" + (path == null || path.isBlank() ? "" : " under " + path) + ":\n");
        files.stream().limit(MAX_FILES_LISTED).forEach(f -> out.append(f).append('\n'));
        if (files.size() > MAX_FILES_LISTED) {
            out.append("...(").append(files.size() - MAX_FILES_LISTED).append(" more - narrow the path)\n");
        }
        return out.toString();
    }

    public String readFile(Repo repo, String path, Integer startLine, Integer endLine) throws IOException {
        Result r = git(repo.dir(), 30, "show", "HEAD:" + safePath(path));
        if (r.exit() != 0) {
            return "No file '" + path + "' on " + repo.branch() + ". Use repo_list_files or repo_search to find the right path.";
        }
        List<String> lines = r.out().lines().toList();
        int from = Math.max(1, startLine == null ? 1 : startLine);
        int to = Math.min(lines.size(), endLine == null ? from + MAX_LINES_READ - 1 : endLine);
        to = Math.min(to, from + MAX_LINES_READ - 1);
        StringBuilder out = new StringBuilder();
        out.append(path).append(" (").append(lines.size()).append(" lines; showing ").append(from).append('-').append(to)
                .append(") ").append(repo.webUrl()).append("/blob/").append(repo.branch()).append('/').append(path).append('\n');
        for (int i = from; i <= to; i++) {
            out.append(i).append(": ").append(clip(lines.get(i - 1), 1000)).append('\n');
        }
        if (to < lines.size()) {
            out.append("...(more lines - call again with start_line=").append(to + 1).append(")\n");
        }
        return out.toString();
    }

    public String search(Repo repo, String query, String path, boolean regex) throws IOException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        List<String> args = new ArrayList<>(List.of("grep", "-n", "-I", "-i", "--full-name", regex ? "-E" : "-F",
                "-e", query, "HEAD"));
        if (path != null && !path.isBlank()) {
            args.add("--");
            args.add(safePath(path));
        }
        Result r = git(repo.dir(), 30, args.toArray(String[]::new));
        if (r.exit() == 1) {
            return "No matches for '" + query + "'.";
        }
        requireOk(r, "search");
        List<String> matches = r.out().lines().map(l -> l.startsWith("HEAD:") ? l.substring(5) : l).toList();
        StringBuilder out = new StringBuilder(matches.size() + " matching lines (path:line:text):\n");
        matches.stream().limit(MAX_MATCHES).forEach(l -> out.append(clip(l, MAX_LINE_CHARS)).append('\n'));
        if (matches.size() > MAX_MATCHES) {
            out.append("...(").append(matches.size() - MAX_MATCHES).append(" more - narrow the query or path)\n");
        }
        return out.toString();
    }

    public String recentCommits(Repo repo, int limit, String path) throws IOException {
        List<String> args = new ArrayList<>(List.of("log", "-n", String.valueOf(Math.max(1, Math.min(limit, 50))),
                "--date=iso-strict", "--format=%h | %ad | %an | %s", "HEAD"));
        if (path != null && !path.isBlank()) {
            args.add("--");
            args.add(safePath(path));
        }
        Result r = git(repo.dir(), 30, args.toArray(String[]::new));
        requireOk(r, "read history");
        return r.out().isBlank() ? "No commits found." : "sha | date | author | subject\n" + r.out()
                + "(link: " + repo.webUrl() + "/commit/<sha>)\n";
    }

    public String showCommit(Repo repo, String sha) throws IOException {
        if (sha == null || !SHA.matcher(sha.trim()).matches()) {
            throw new IllegalArgumentException("sha must be a commit hash");
        }
        Result r = git(repo.dir(), 30, "show", "--format=fuller", "--stat", "--patch", sha.trim(), "--");
        if (r.exit() != 0) {
            return "No commit " + sha + ".";
        }
        String out = r.out();
        if (out.length() > MAX_DIFF_CHARS) {
            out = out.substring(0, MAX_DIFF_CHARS) + "\n...(diff truncated - read the changed files directly)";
        }
        return repo.webUrl() + "/commit/" + sha.trim() + "\n" + out;
    }

    // ---- plumbing ----

    private record Result(int exit, String out) { }

    /** The git command line up to the subcommand. */
    List<String> baseCommand() throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "credential.helper="));
        command.addAll(tlsArgs());
        return command;
    }

    private List<String> tlsArgs() throws IOException {
        if (tlsArgs == null) {
            List<String> found = new ArrayList<>();
            for (String key : TLS_SETTINGS) {
                // Read with the host's config on purpose: these keys say whom to trust, not who we are.
                Result r = run(new ProcessBuilder("git", "config", "--get", key), 10, "config");
                if (r.exit() == 0 && !r.out().isBlank()) {
                    found.add("-c");
                    found.add(key + "=" + r.out().trim());
                }
            }
            tlsArgs = List.copyOf(found);
        }
        return tlsArgs;
    }

    /** Turns a child process environment into an anonymous one: no identity of the host reaches git. */
    void isolate(Map<String, String> env) {
        env.keySet().removeIf(name -> !KEPT_ENV.contains(name.toUpperCase(Locale.ROOT)));
        env.put("HOME", noHome.toString());
        env.put("USERPROFILE", noHome.toString());
        env.put("XDG_CONFIG_HOME", noHome.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", noHome.resolve("gitconfig").toString());
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "");
        env.put("GCM_INTERACTIVE", "never");
        env.put("GIT_ALLOW_PROTOCOL", "https");
    }

    private Result git(Path dir, int timeoutSeconds, String... args) throws IOException {
        List<String> command = baseCommand();
        if (dir != null) {
            command.add("--git-dir=" + dir);
        }
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        isolate(pb.environment());
        return run(pb, timeoutSeconds, args[0]);
    }

    private static Result run(ProcessBuilder pb, int timeoutSeconds, String what) throws IOException {
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process = pb.start();
        String out;
        try (InputStream in = process.getInputStream()) {
            out = readCapped(in);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + what + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted");
        }
        return new Result(process.exitValue(), out);
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        boolean truncated = false;
        while ((n = in.read(chunk)) != -1) {
            if (buffer.size() < OUTPUT_LIMIT_BYTES) {
                buffer.write(chunk, 0, Math.min(n, OUTPUT_LIMIT_BYTES - buffer.size()));
            } else {
                truncated = true; // keep draining so git can exit
            }
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        return truncated ? text + "\n...(output truncated)" : text;
    }

    private static void requireOk(Result r, String what) throws IOException {
        if (r.exit() != 0) {
            throw new IOException("git could not " + what);
        }
    }

    /** A repository path as git should see it: never an option, never outside the tree. */
    static String safePath(String path) {
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.startsWith("-") || p.contains("\0") || p.contains("\n") || p.contains(":")
                || List.of(p.split("/")).contains("..")) {
            throw new IllegalArgumentException("invalid path: " + path);
        }
        return p;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
