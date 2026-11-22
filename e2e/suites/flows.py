"""Request flows: learned from traces, checked only once approved, broken ones open incidents."""
import threading
import time
from datetime import datetime, timedelta, timezone

from support import RUN, call, check, finish, register, wait_for

def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


A, _ = register("Owner")
V, v_email = register("Viewer")
_, p, _ = call("POST", "/api/projects", {"name": "Flows"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": v_email, "role": "VIEWER"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
KEY = {"X-Api-Key": k["key"]}
stop = False
def beat():
    while not stop:
        for svc in ("gateway", "orders", "payments"):
            call("POST", "/api/process/heartbeat", {"serviceName": svc, "status": "UP"}, headers=KEY)
        time.sleep(4)
threading.Thread(target=beat, daemon=True).start()

def log(svc, msg, trace, at, level=2):
    s = call("POST", "/api/logs", {"serviceName": svc, "logLevel": level, "rawMessage": msg, "traceId": trace, "timestamp": iso(at)}, headers=KEY)[0]
    assert s in (200, 201, 202), s

# --- history: 15 POST /orders (gateway->orders->payments), 15 GET /orders (gateway->orders), 5 health (gateway only)
now = datetime.now(timezone.utc)
for n in range(15):
    t0 = now - timedelta(minutes=90 - n)
    tr = f"h-post-{RUN}-{n}"
    log("gateway", f"POST /orders {1000 + n}", tr, t0)
    log("orders", "creating order", tr, t0 + timedelta(seconds=1))
    log("payments", "charging card", tr, t0 + timedelta(seconds=2))
for n in range(15):
    t0 = now - timedelta(minutes=80 - n)
    tr = f"h-get-{RUN}-{n}"
    log("gateway", f"GET /orders/{n}", tr, t0)
    log("orders", "reading order", tr, t0 + timedelta(milliseconds=300))
for n in range(5):
    log("gateway", "GET /health", f"h-health-{RUN}-{n}", now - timedelta(minutes=70 - n))
time.sleep(4)

check("viewer cannot trigger learning", call("POST", "/api/process/flows/learn", token=V, project=P)[0], 403)
s, r, _ = call("POST", "/api/process/flows/learn", token=A, project=P)
flows = r["flows"] if s == 200 else []
key_of = lambda f: (f["entryService"], f["routeExample"].split(" ")[0] + " " + f["routeExample"].split(" ")[1].split("/")[1] if f["routeExample"] else "*", f["targetService"])
summary = sorted((f["entryService"], f["routeExample"] is not None, f["targetService"], f["learnedSamples"]) for f in flows)
check("learned 3 suggestions (per route; broad gateway->orders is only 86%)", (s, r.get("added"), summary),
      (200, 3, sorted([("gateway", True, "orders", 15), ("gateway", True, "orders", 15), ("gateway", True, "payments", 15)])))
pay = next((f for f in flows if f["targetService"] == "payments"), None)
check("suggestion stats + deadline", (pay["status"], pay["learnedReachPercent"], pay["learnedP95Seconds"], pay["maxSeconds"], pay["createdBy"]) if pay else None,
      ("SUGGESTED", 100.0, 2.0, 6, "moses"))
check("example trace kept", (pay or {}).get("exampleTraceId", "").startswith(f"h-post-{RUN}"), True)
check("nothing is checked before approval", call("GET", "/api/process/incidents?state=OPEN", token=V, project=P)[1], [])
s, r2, _ = call("POST", "/api/process/flows/learn", token=A, project=P)
check("learning again adds no duplicates", (r2["added"], len(r2["flows"])), (0, 3))

get_orders = next(f for f in flows if f["targetService"] == "orders" and f["routeExample"].startswith("GET"))
check("viewer cannot approve", call("PATCH", f"/api/process/flows/{pay['id']}", {"status": "ACTIVE"}, token=V, project=P)[0], 403)
check("reject a suggestion", call("PATCH", f"/api/process/flows/{get_orders['id']}", {"status": "REJECTED"}, token=A, project=P)[1]["status"], "REJECTED")
_, r3, _ = call("POST", "/api/process/flows/learn", token=A, project=P)
check("rejected stays rejected after learning", next(f["status"] for f in r3["flows"] if f["id"] == get_orders["id"]), "REJECTED")
s, ap, _ = call("PATCH", f"/api/process/flows/{pay['id']}", {"status": "ACTIVE", "maxSeconds": 5}, token=A, project=P)
check("approve with a tuned deadline", (s, ap["status"], ap["maxSeconds"], ap["decidedBy"] is not None), (200, "ACTIVE", 5, True))
check("can't go back to suggestion", call("PATCH", f"/api/process/flows/{pay['id']}", {"status": "SUGGESTED"}, token=A, project=P)[0], 400)

# --- live traffic: 6 fine, 3 never reach payments, 1 reaches it but errors in orders
base = datetime.now(timezone.utc) - timedelta(seconds=20)
for n in range(10):
    tr = f"live-{RUN}-{n}"
    t0 = base + timedelta(milliseconds=50 * n)
    log("gateway", f"POST /orders {5000 + n}", tr, t0)
    if n in (6, 7, 8):
        log("orders", "creating order", tr, t0 + timedelta(seconds=1))
    elif n == 9:
        log("orders", "stock check failed", tr, t0 + timedelta(seconds=1), level=4)
        log("payments", "charging card", tr, t0 + timedelta(seconds=2))
    else:
        log("orders", "creating order", tr, t0 + timedelta(seconds=1))
        log("payments", "charging card", tr, t0 + timedelta(seconds=2))
# A GET (other route) that never reaches payments must not count.
log("gateway", "GET /orders/77", f"live-get-{RUN}", base)

def flow_incident():
    s, body, _ = call("GET", "/api/process/incidents?state=OPEN", token=V, project=P)
    return [i for i in body if i["kind"] == "FLOW_BROKEN"] if s == 200 else None
fi = wait_for(flow_incident, 40)
check("broken flow opened an incident", bool(fi), True)
if fi:
    i = fi[0]
    check("incident numbers", (i["serviceName"], i["flowEntry"], i["checkedTraces"], i["brokenTraces"], i["flowMaxSeconds"]),
          ("payments", "gateway", 10, 4, 5))
    check("broken examples say where and why", sorted((b["reason"], b["stoppedAt"]) for b in i["brokenExamples"]),
          sorted([("NOT_REACHED", "orders")] * 3 + [("ERROR", "orders")]))
s, body, _ = call("GET", "/api/chat/messages?channel=team", token=V, project=P)
ev = [m["event"] for m in body["messages"] if m["authorType"] == "SYSTEM" and m["event"]["incidentKind"] == "FLOW_BROKEN"]
check("chat alert for the flow", [(e["type"], e["flowEntry"], e["serviceName"], e["brokenTraces"], e["checkedTraces"], e["stoppedAt"]) for e in ev],
      [("INCIDENT_OPENED", "gateway", "payments", 4, 10, "orders")])
time.sleep(9)
check("no duplicate incident", len(flow_incident() or []), 1)
_, fl, _ = call("GET", "/api/process/flows", token=V, project=P)
check("flow shows its last check", next(f["lastCheckedTraces"] is not None for f in fl if f["id"] == pay["id"]), True)

# --- pausing ends it ---
call("PATCH", f"/api/process/flows/{pay['id']}", {"status": "PAUSED"}, token=A, project=P)
check("pausing the flow closes its incident", bool(wait_for(lambda: flow_incident() == [], 20)), True)

# --- by hand ---
check("entry == target rejected", call("POST", "/api/process/flows", {"entryService": "orders", "targetService": "orders"}, token=A, project=P)[0], 400)
check("bad deadline rejected", call("POST", "/api/process/flows", {"entryService": "gateway", "targetService": "orders", "maxSeconds": 0}, token=A, project=P)[0], 400)
s, man, _ = call("POST", "/api/process/flows", {"entryService": "gateway", "targetService": "payments", "routeExample": "PUT /cart 55", "maxSeconds": 10}, token=A, project=P)
check("manual flow is active at once", (s, man["status"], man["route"], man["maxSeconds"]), (201, "ACTIVE", "put /cart <n>", 10))
s, man2, _ = call("POST", "/api/process/flows", {"entryService": "gateway", "targetService": "payments", "routeExample": "PUT /cart 99", "maxSeconds": 12}, token=A, project=P)
check("same flow again updates it", (s, man2["id"] == man["id"], man2["maxSeconds"]), (200, True, 12))
check("delete", call("DELETE", f"/api/process/flows/{man['id']}", token=A, project=P)[0], 204)

finish(expected=23)
