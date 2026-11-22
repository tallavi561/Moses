"""Per-environment architecture, chat and AI models; keys never come back."""
from support import RUN, call, check, finish, last_prompt, register, register_with_id, url, use_fake_model, wait_for

A, a_id = register_with_id("Owner")
B, _ = register("Member")
_, p, _ = call("POST", "/api/projects", {"name": "Shop"}, token=A)
P = p["id"]
_, inv, _ = call("POST", f"/api/projects/{P}/invites", token=A)
call("POST", f"/api/invites/{inv['token']}/accept", token=B)
call("POST", f"/api/projects/{P}/environments", {"name": "staging"}, token=A)


def agent_message(token, env, channel):
    _, page, _ = call("GET", f"/api/chat/messages?channel={channel}", token=token, project=P, env=env)
    found = [m for m in page["messages"] if m["authorType"] == "AGENT"]
    return found[-1] if found else None


print("--- architecture per environment ---")
k_prod = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)[1]["key"]
k_stg = call("POST", f"/api/projects/{P}/keys", {"environment": "staging"}, token=A)[1]["key"]
hb = lambda key, body: call("POST", "/api/process/heartbeat", body, headers={"X-Api-Key": key})[0]
check("prod heartbeat", hb(k_prod, {"serviceName": "payments-api", "status": "UP", "connections": ["orders-db"]}), 202)
check("prod heartbeat 2", hb(k_prod, {"serviceName": "orders-db", "status": "UP"}), 202)
check("staging heartbeat", hb(k_stg, {"serviceName": "payments-api-next", "status": "DEGRADED"}), 202)
names = lambda env: sorted(n["serviceName"] for n in call("GET", "/api/process/state", token=A, project=P, env=env)[1])
check("prod sees only prod services", names("prod"), ["orders-db", "payments-api"])
check("staging sees only staging services", names("staging"), ["payments-api-next"])
conns = lambda env: len(call("GET", "/api/process/connections", token=A, project=P, env=env)[1])
check("connections are per environment", (conns("prod"), conns("staging")), (1, 0))
check("same name in two environments is allowed", hb(k_stg, {"serviceName": "payments-api", "status": "UP"}), 202)
call("DELETE", "/api/process/nodes/payments-api", token=A, project=P, env="staging")
check("deleting in staging leaves prod alone", (names("prod"), names("staging")),
      (["orders-db", "payments-api"], ["payments-api-next"]))

print("--- chat per environment ---")
call("POST", "/api/chat/messages", {"channel": "team", "text": "prod only"}, token=A, project=P, env="prod")
call("POST", "/api/chat/messages", {"channel": "team", "text": "staging only"}, token=B, project=P, env="staging")
texts = lambda env: [m["text"] for m in call("GET", "/api/chat/messages?channel=team", token=A, project=P, env=env)[1]["messages"]]
check("prod chat", texts("prod"), ["prod only"])
check("staging chat", texts("staging"), ["staging only"])
check("unknown environment -> 404", call("GET", "/api/chat/messages?channel=team", token=A, project=P, env="qa")[0], 404)

print("--- AI model settings ---")
_, ov, _ = call("GET", f"/api/projects/{P}/llm", token=B)
check("nothing set at first, server can store keys", (ov["project"], ov["environments"], ov["canStoreKeys"]),
      (None, {"prod": None, "staging": None}, True))

call("POST", "/api/chat/messages", {"channel": f"agent:{a_id}", "text": "hello?"}, token=A, project=P)
r = wait_for(lambda: agent_message(A, "prod", f"agent:{a_id}"), 30)
check("agent without a model says so", (r is not None and r["failed"], "Settings" in (r or {}).get("text", "")), (True, True))

PROD_KEY = f"fake-prod-key-{RUN}"
STAGING_KEY = f"fake-staging-key-{RUN}x"

check("member cannot set the model", use_fake_model(P, B, "fake-member-key")[0], 403)
s, body, _ = use_fake_model(P, A, "fake-wrong-key")
check("a wrong key is refused with the provider's reason", (s, body.get("message")), (422, "The provider rejected this API key."))
s, body, _ = use_fake_model(P, A, PROD_KEY, model="no-such-model")
check("an unknown model is refused", (s, "no model" in str(body)), (422, True))
s, ov, raw = use_fake_model(P, A, PROD_KEY)
check("a good key is accepted", (s, ov["project"]["keyHint"]), (200, PROD_KEY[-4:]))
check("the key never comes back", PROD_KEY in raw or PROD_KEY in call("GET", f"/api/projects/{P}/llm", token=B)[2], False)
s, ov, _ = use_fake_model(P, A, "", model="fake-model-2")
check("empty key keeps the stored key (model change only)", (s, ov["project"]["model"]), (200, "fake-model-2"))
s, ov, _ = use_fake_model(P, A, STAGING_KEY, environment="staging")
check("staging gets its own key", (s, ov["environments"]["staging"] is not None, ov["environments"]["prod"]), (200, True, None))
check("internal endpoint needs the token",
      call("GET", f"/internal/projects/{P}/llm?environment=prod", base=url("auth"))[0], 403)
check("project info endpoint needs the token",
      call("GET", f"/internal/projects/{P}/info", base=url("auth"))[0], 403)
check("agent endpoint needs the token",
      call("POST", "/internal/agent/reply", {"question": "hi"}, base=url("ai"),
           headers={"X-Moses-Project-Id": P, "X-Moses-Environment": "prod"})[0], 403)

print("--- AI sees only its own environment ---")
check("free-text question in staging", call("POST", "/api/ai/query", {"question": "any errors?"}, token=A, project=P, env="staging")[0], 200)
prompt = str(last_prompt(STAGING_KEY))
check("staging's key was used, with staging's services only", ("payments-api-next" in prompt, "orders-db" in prompt), (True, False))
check("free-text question in prod", call("POST", "/api/ai/query", {"question": "any errors?"}, token=A, project=P, env="prod")[0], 200)
prompt = str(last_prompt(PROD_KEY))
check("prod's key was used, with prod's services only", ("orders-db" in prompt, "payments-api-next" in prompt), (True, False))

call("POST", "/api/chat/messages", {"channel": "team", "text": "@moses which services run here?"}, token=B, project=P, env="staging")
r = wait_for(lambda: agent_message(B, "staging", "team"), 60)
check("agent replied in staging", r is not None, True)
check("prod chat untouched by the staging answer", texts("prod"), ["prod only"])

finish(expected=29)
