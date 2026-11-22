package com.moses.monitor.ai.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's git identity must never travel with a URL a MoSes user typed. Each test plants a fake
 * credential, proves plain git sends it to a loopback server, then that RepoReader's git doesn't.
 */
class RepoReaderIsolationTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private int requests;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++;
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authorizations.add(auth);
            } else {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
            }
            exchange.sendResponseHeaders(auth == null ? 401 : 404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void credentialHelperIsNotUsed() throws Exception {
        assertNotSent(configFile("[credential]\n\thelper = \"!f() { echo username=host; echo password=fake; }; f\"\n"));
    }

    @Test
    void authorizationHeaderFromConfigIsNotSent() throws Exception {
        assertNotSent(configFile("[http]\n\textraHeader = Authorization: Bearer fake\n"));
    }

    @Test
    void netrcIsNotRead() throws Exception {
        Path home = Files.createDirectory(tmp.resolve("home"));
        for (String name : List.of(".netrc", "_netrc")) {
            Files.writeString(home.resolve(name), "machine 127.0.0.1 login host password fake\n");
        }
        assertNotSent(Map.of("HOME", home.toString(), "USERPROFILE", home.toString(),
                "GIT_CONFIG_GLOBAL", tmp.resolve("none").toString()));
    }

    @Test
    void configInEnvironmentVariablesIsIgnored() throws Exception {
        assertNotSent(Map.of("GIT_CONFIG_GLOBAL", tmp.resolve("none").toString(), "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http.extraHeader", "GIT_CONFIG_VALUE_0", "Authorization: Bearer fake"));
    }

    @Test
    void askpassProgramIsNotRun() throws Exception {
        Path script = tmp.resolve("askpass.sh");
        Files.writeString(script, "#!/bin/sh\necho fake\n");
        script.toFile().setExecutable(true);
        assertNotSent(Map.of("GIT_CONFIG_GLOBAL", tmp.resolve("none").toString(),
                "GIT_ASKPASS", script.toString().replace('\\', '/')));
    }

    @Test
    void servicesOwnSecretsDoNotReachGit() {
        Map<String, String> env = new java.util.HashMap<>(Map.of("JWT_SECRET", "x", "INTERNAL_API_TOKEN", "x",
                "SECRETS_ENCRYPTION_KEY", "x", "MONGODB_URI", "x", "GITHUB_TOKEN", "x", "Path", "bin"));
        new RepoReader(tmp.toString()).isolate(env);
        for (String name : List.of("JWT_SECRET", "INTERNAL_API_TOKEN", "SECRETS_ENCRYPTION_KEY", "MONGODB_URI", "GITHUB_TOKEN")) {
            assertFalse(env.containsKey(name), name);
        }
        assertTrue(env.containsKey("Path"), "git still has to be found");
    }

    private Map<String, String> configFile(String content) throws Exception {
        Path config = tmp.resolve("hostile.gitconfig");
        Files.writeString(config, content);
        return Map.of("GIT_CONFIG_GLOBAL", config.toString());
    }

    private void assertNotSent(Map<String, String> host) throws Exception {
        // The control never sees the real machine's config or credentials, only the planted fake.
        lsRemote(List.of("git"), env -> {
            env.keySet().removeIf(k -> k.toUpperCase().startsWith("GIT_"));
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.putAll(host);
        });
        assertFalse(authorizations.isEmpty(), "control: plain git should have sent the fake credential");
        authorizations.clear();
        requests = 0;

        RepoReader reader = new RepoReader(tmp.resolve("cache").toString());
        lsRemote(reader.baseCommand(), env -> {
            env.putAll(host);
            reader.isolate(env);
            env.put("GIT_ALLOW_PROTOCOL", "http"); // only because the test server is plain http
        });
        assertTrue(requests > 0, "isolated git should still reach the server");
        assertTrue(authorizations.isEmpty(), "sent: " + authorizations);
    }

    private void lsRemote(List<String> git, java.util.function.Consumer<Map<String, String>> environment) throws Exception {
        List<String> command = new ArrayList<>(git);
        command.addAll(List.of("ls-remote", "http://127.0.0.1:" + server.getAddress().getPort() + "/o/r.git"));
        ProcessBuilder pb = new ProcessBuilder(command);
        environment.accept(pb.environment());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null")));
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "git hung");
    }
}
