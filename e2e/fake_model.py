"""
A stand-in OpenAI-compatible provider, so the suites never need a real key or the internet.

It answers /models (a key containing "wrong" is rejected), gives fixed replies to the three kinds
of prompt MoSes sends, and remembers the last request per API key (GET /last/<key>).
"""
import json
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODELS = ("fake-model", "fake-model-2")

DASHBOARD_EDIT = {"services": ["checkout", "ghost"], "metric": "cpu", "minutesBack": 120,
                  "summary": "Showing CPU for checkout over the last hour.", "note": None}
INVESTIGATION = {"summary": "ה-DB pool נגמר תחת עומס",
                 "rootCause": "Connection leak in the orders repository exhausts the DB pool",
                 "suggestedFix": "Close connections in OrderRepository.save; raise the pool to 50 meanwhile",
                 "confidence": "Medium", "evidence": ["x6 db timeout in checkout", "same as the earlier solved incident"]}
# Long enough that a second "investigate" request reliably finds the first one still running.
INVESTIGATION_SECONDS = 3


def _query():
    now = datetime.now(timezone.utc)
    stamp = "%Y-%m-%dT%H:%M:%SZ"
    return {"words": [], "service": None, "logLevel": 4, "traceId": None,
            "from": (now - timedelta(hours=1)).strftime(stamp), "to": now.strftime(stamp)}


def start():
    """Starts the server on a free loopback port and returns it."""
    last = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _send(self, value, status=200):
            raw = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _key(self):
            return self.headers.get("Authorization", "").removeprefix("Bearer ")

        def _body(self):
            if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))
            raw = b""
            while size := int(self.rfile.readline().strip(), 16):
                raw += self.rfile.read(size)
                self.rfile.readline()
            self.rfile.readline()
            return raw

        def do_GET(self):
            if self.path.startswith("/last/"):
                return self._send(last.get(self.path[len("/last/"):], {}))
            if "wrong" in self._key():
                return self._send({"error": "invalid key"}, 401)
            self._send({"data": [{"id": model} for model in MODELS]})

        def do_POST(self):
            request = json.loads(self._body())
            last[self._key()] = request
            system = request["messages"][0]["content"]
            if "investigating one incident" in system:
                time.sleep(INVESTIGATION_SECONDS)
                content = "Result:\n" + json.dumps(INVESTIGATION)
            elif "structured search" in system:
                content = json.dumps(_query())
            else:
                content = json.dumps(DASHBOARD_EDIT)
            self._send({"choices": [{"message": {"role": "assistant", "content": content}}]})

    return ThreadingHTTPServer(("127.0.0.1", 0), Handler)
