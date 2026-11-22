"""What every end-to-end suite uses: HTTP calls to the test stack, checks, and a strict finish()."""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

RUN = uuid.uuid4().hex[:10]
PASSWORD = "e2e-test-pass-1"

_results = []
# Straight to the stack: the runner's environment points HTTP proxies at a dead port on purpose.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def url(name):
    """Base URL of one part of the stack: gateway, chat, ai, auth or model."""
    value = os.environ.get(f"E2E_{name.upper()}_URL")
    if not value:
        sys.exit("run the suites through e2e/run.py, which starts the stack")
    return value


def call(method, path, body=None, token=None, project=None, env="prod", base=None, headers=None):
    """(status, parsed body, raw body)."""
    sent = {"Content-Type": "application/json"}
    if token:
        sent["Authorization"] = "Bearer " + token
    if project:
        sent["X-Project-Id"] = project
        sent["X-Environment"] = env
    sent.update(headers or {})
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request((base or url("gateway")) + path, data=data, method=method, headers=sent)
    try:
        with _opener.open(request, timeout=120) as response:
            status, raw = response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read().decode()
    try:
        return status, (json.loads(raw) if raw else None), raw
    except ValueError:
        return status, raw, raw


def call_json(*args, **kwargs):
    """(status, parsed body)."""
    return call(*args, **kwargs)[:2]


def check(name, got, expected):
    ok = got == expected
    _results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": got {got!r}, expected {expected!r}"), flush=True)


def wait_for(fn, timeout=90, step=2):
    end = time.time() + timeout
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(step)
    return None


def create_user(name):
    """A new account: (token, user)."""
    status, body, _ = call("POST", "/api/auth/register", {
        "name": name, "email": f"{name.lower()}-{uuid.uuid4().hex[:10]}@example.com", "password": PASSWORD})
    assert status == 200, (status, body)
    return body["token"], body["user"]


def register(name):
    """A new account: (token, email)."""
    token, user = create_user(name)
    return token, user["email"]


def register_with_id(name):
    """A new account: (token, user id)."""
    token, user = create_user(name)
    return token, user["id"]


def use_fake_model(project, token, key, model="fake-model", environment=None):
    """Saves the fake OpenAI-compatible model as the project's (or environment's) model, like an owner would."""
    path = f"/api/projects/{project}" + (f"/environments/{environment}" if environment else "") + "/llm"
    return call("PUT", path, {"provider": "openai-compatible", "model": model, "apiKey": key,
                              "baseUrl": url("model") + "/v1"}, token=token)


def last_prompt(key):
    """The last request the fake model got with this API key."""
    return call("GET", f"/last/{key}", base=url("model"))[1]


def finish(expected):
    """
    Ends the suite. Fails on a failed check and on a different number of checks than expected, so
    a check inside an `if` that stopped being reached can't turn into a silent pass.
    """
    passed, ran = sum(_results), len(_results)
    print(f"\n{passed}/{expected} passed")
    if ran != expected:
        print(f"FAIL the suite ran {ran} checks, expected {expected}")
    sys.exit(0 if passed == ran == expected else 1)
