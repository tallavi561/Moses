"""The test stack: the seven services from their jars, on free loopback ports and a throwaway database."""
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import testdb

ROOT = Path(__file__).resolve().parent.parent
SERVICES = ("ingestion-service", "search-service", "process-monitor-service", "ai-service",
            "auth-service", "chat-service", "api-gateway")

# Everything a child process may inherit. Never MONGODB_URI, API keys, tokens or SPRING_* overrides:
# a developer's shell often has the real ones.
KEPT_ENV = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL",
            "JAVA_HOME", "USERPROFILE", "HOME"}
# Outbound HTTP(S) goes to a closed port: a suite can't reach a real provider or GitHub by accident.
DEAD_PROXY = ("127.0.0.1", 9)

# The production defaults are minutes; the suites need seconds.
FAST_TIMINGS = {
    "INCIDENT_CHECK_INTERVAL_MS": "4000",
    "INCIDENT_INITIAL_DELAY_MS": "5000",
    "INCIDENT_WINDOW_MINUTES": "1",
    "STALE_AFTER_SECONDS": "15",
    "STALE_CHECK_INTERVAL_MS": "3000",
    "FLOW_MIN_SAMPLES": "10",
    "FLOW_MIN_TRACE_AGE_SECONDS": "60",
    "FLOW_LEARN_INITIAL_DELAY_MS": "3600000",
}


def free_ports(count):
    sockets = [socket.socket() for _ in range(count)]
    for s in sockets:
        s.bind(("127.0.0.1", 0))
    ports = [s.getsockname()[1] for s in sockets]
    for s in sockets:
        s.close()
    return ports


def clean_environment(environ):
    env = {name: value for name, value in environ.items() if name.upper() in KEPT_ENV}
    proxy = "http://%s:%d" % DEAD_PROXY
    env.update(HTTP_PROXY=proxy, HTTPS_PROXY=proxy, NO_PROXY="127.0.0.1,localhost")
    return env


class Stack:
    def __init__(self, mongo_port, log_dir):
        self.mongo_port = mongo_port
        self.log_dir = Path(log_dir)
        self.database = testdb.new_name()
        self.ports = dict(zip(SERVICES, free_ports(len(SERVICES))))
        self.work = None
        self.processes = {}
        self.logs = []

    def url(self, service):
        return f"http://127.0.0.1:{self.ports[service]}"

    def environment(self, service):
        env = clean_environment(os.environ)
        env.update(FAST_TIMINGS)
        env.update(
            PORT=str(self.ports[service]),
            SERVER_ADDRESS="127.0.0.1",
            MONGODB_URI=f"mongodb://{testdb.HOST}:{self.mongo_port}/{self.database}",
            JWT_SECRET=self.jwt_secret,
            SECRETS_ENCRYPTION_KEY=self.encryption_key,
            INTERNAL_API_TOKEN=self.internal_token,
            AGENT_REPO_CACHE_DIR=str(self.work / "repos"),
            INGESTION_SERVICE_URL=self.url("ingestion-service"),
            SEARCH_SERVICE_URL=self.url("search-service"),
            PROCESS_MONITOR_SERVICE_URL=self.url("process-monitor-service"),
            AI_SERVICE_URL=self.url("ai-service"),
            AUTH_SERVICE_URL=self.url("auth-service"),
            CHAT_SERVICE_URL=self.url("chat-service"))
        return env

    def start(self):
        jars = {name: ROOT / name / "target" / f"{name}.jar" for name in SERVICES}
        missing = [name for name, jar in jars.items() if not jar.is_file()]
        if missing:
            raise RuntimeError(f"no jar for {', '.join(missing)}: run `mvn package` first")
        if not testdb.ping(self.mongo_port):
            raise RuntimeError(f"no MongoDB on {testdb.HOST}:{self.mongo_port}")
        self.jwt_secret, self.encryption_key, self.internal_token = (secrets.token_hex(24) for _ in range(3))
        self.work = Path(tempfile.mkdtemp(prefix="moses-e2e-"))
        self.log_dir.mkdir(parents=True, exist_ok=True)
        java = shutil.which("java")
        if not java:
            raise RuntimeError("java (21) is not on PATH")
        host, port = DEAD_PROXY
        for name, jar in jars.items():
            copy = self.work / f"e2e-{name.removesuffix('-service')}.jar"
            shutil.copyfile(jar, copy)  # a copy: rebuilding or restarting the local stack doesn't disturb a run
            log = open(self.log_dir / f"{name}.log", "wb")
            self.logs.append(log)
            self.processes[name] = subprocess.Popen(
                [java, "-Xmx384m", f"-Dhttps.proxyHost={host}", f"-Dhttps.proxyPort={port}", "-jar", str(copy)],
                cwd=self.work, env=self.environment(name), stdout=log, stderr=subprocess.STDOUT)
        self._wait_until_ready()

    def _wait_until_ready(self, timeout=180):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def status(service, path, body=None):
            request = urllib.request.Request(self.url(service) + path, data=body,
                                             headers={"Content-Type": "application/json"})
            try:
                with opener.open(request, timeout=5) as response:
                    return response.status
            except urllib.error.HTTPError as error:
                return error.code
            except OSError:
                return None

        probes = {
            "api-gateway": lambda: status("api-gateway", "/api/auth/login",
                                          b'{"email":"nobody@example.com","password":"nopenope1"}') == 401,
            "chat-service": lambda: status("chat-service", "/api/chat/me") == 401,
            "ai-service": lambda: status("ai-service", "/internal/llm/check", b"{}") == 403,
        }
        deadline = time.time() + timeout
        waiting = set(SERVICES)
        while waiting:
            for name, process in self.processes.items():
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited on start; see {self.log_dir / (name + '.log')}")
            for name in list(waiting):
                probe = probes.get(name, lambda: status(name, "/") is not None)
                if probe():
                    waiting.remove(name)
            if waiting:
                if time.time() > deadline:
                    raise RuntimeError(f"not ready after {timeout}s: {', '.join(sorted(waiting))}")
                time.sleep(1.5)

    def stop(self):
        for process in self.processes.values():
            if process.poll() is None:
                if os.name == "nt":
                    # java on PATH may be a launcher with the real JVM as its child
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
                else:
                    process.terminate()
        for process in self.processes.values():
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
        for log in self.logs:
            log.close()
        if self.processes and not testdb.drop(self.mongo_port, self.database):
            print(f"warning: could not drop {self.database}")
        if self.work:
            shutil.rmtree(self.work, ignore_errors=True)
