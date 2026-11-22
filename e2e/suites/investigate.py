"""Investigate with AI: what the model is given, what is stored, who may start one."""
import json
import threading
import time

from support import RUN, call, check, finish, last_prompt, register, register_with_id, use_fake_model, wait_for

MODEL_KEY = f"fake-investigate-{RUN}"
A, A_ID = register_with_id("Owner")
V, v_email = register("Viewer")
_, p, _ = call("POST", "/api/projects", {"name": "Investigate"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": v_email, "role": "VIEWER"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
KEY = {"X-Api-Key": k["key"]}
stop = False
def beat():
    while not stop:
        for svc in ("orders", "checkout"):
            call("POST", "/api/process/heartbeat", {"serviceName": svc, "status": "UP"}, headers=KEY)
        time.sleep(4)
threading.Thread(target=beat, daemon=True).start()
def log(svc, msg, trace=None):
    call("POST", "/api/logs", {"serviceName": svc, "logLevel": 4, "rawMessage": msg, "traceId": trace}, headers=KEY)
def open_of(svc):
    s, body, _ = call("GET", "/api/process/incidents?state=OPEN", token=V, project=P)
    return [i for i in body if i["serviceName"] == svc] if s == 200 else None

# earlier incident, solved by the team
for n in range(6):
    log("orders", f"Order {n} failed: DB timeout after {100 + n}ms")
first = wait_for(lambda: open_of("orders"), 60)
IA = first[0]["id"]
call("PUT", f"/api/process/incidents/{IA}/review", {"status": "SOLVED", "rootCause": "pool exhausted", "resolution": "Raised the pool size from 10 to 50"}, token=A, project=P)
wait_for(lambda: not open_of("orders"), 150, 5)

# the new one
for n in range(6):
    log("checkout", f"order {900 + n} failed: db timeout after {5000 + n}ms", trace=f"tr-{RUN}-{n}")
second = wait_for(lambda: open_of("checkout"), 60)
IB = second[0]["id"]

check("no model yet -> 409 with a clear message", call("POST", f"/api/ai/incidents/{IB}/investigate", {"language": "he"}, token=A, project=P)[:2][0], 409)
check("viewer cannot investigate", call("POST", f"/api/ai/incidents/{IB}/investigate", {"language": "he"}, token=V, project=P)[0], 403)
s, _, raw = use_fake_model(P, A, MODEL_KEY)
check("fake model configured", s, 200)
check("unknown incident -> 404", call("POST", "/api/ai/incidents/000000000000000000000000/investigate", {"language": "he"}, token=A, project=P)[0], 404)

s, started, _ = call("POST", f"/api/ai/incidents/{IB}/investigate", {"language": "he"}, token=A, project=P)
check("starts in the background", (s, started.get("status") if isinstance(started, dict) else started), (202, "RUNNING"))
check("a second run while one is running -> 409", call("POST", f"/api/ai/incidents/{IB}/investigate", {"language": "he"}, token=A, project=P)[0], 409)
def done():
    s, i, _ = call("GET", f"/api/process/incidents/{IB}", token=V, project=P)
    ai = i.get("aiInvestigation") if s == 200 else None
    return ai if ai and ai["status"] != "RUNNING" else None
ai = wait_for(done, 60)
check("investigation finished", (ai or {}).get("status"), "DONE")
if ai:
    check("fields stored", (ai["rootCause"], ai["suggestedFix"][:13], ai["confidence"], len(ai["evidence"]), ai["requestedBy"]),
          ("Connection leak in the orders repository exhausts the DB pool", "Close connect", "medium", 2, A_ID))
    check("summary in Hebrew kept", ai["summary"].startswith("\u05d4-DB"), True)
    check("model recorded", "fake-model" in (ai["model"] or ""), True)
last = last_prompt(MODEL_KEY)
prompt = json.dumps(last, ensure_ascii=False)
check("prompt asks for Hebrew", "in Hebrew" in prompt, True)
check("prompt has the incident's errors", "db timeout" in prompt, True)
check("prompt has the solved similar incident and its fix", "Raised the pool size from 10 to 50" in prompt, True)
check("prompt has a trace of an error", "trace_of_an_error" in prompt, True)
s, lib, _ = call("GET", "/api/process/incidents/library?q=connection+leak", token=V, project=P)
check("library finds the AI's root cause", [i["id"] for i in lib], [IB])
s, i, _ = call("GET", f"/api/process/incidents/{IB}", token=V, project=P)
check("team review untouched by the AI", i["reviewStatus"], "UNREVIEWED")
check("investigating again is allowed when done", call("POST", f"/api/ai/incidents/{IB}/investigate", {"language": "en"}, token=A, project=P)[0], 202)
wait_for(done, 60)

finish(expected=17)
