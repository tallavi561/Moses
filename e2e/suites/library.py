"""The incident library: reviews, 'seen before' across services and environments, search."""
import threading
import time

from support import call, check, finish, register, wait_for

A, _ = register("Owner")
V, v_email = register("Viewer")
_, p, _ = call("POST", "/api/projects", {"name": "Library"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": v_email, "role": "VIEWER"}, token=A)
call("POST", f"/api/projects/{P}/environments", {"name": "staging"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
_, ks, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "staging"}, token=A)
KEY = {"X-Api-Key": k["key"]}
SKEY = {"X-Api-Key": ks["key"]} if ks and "key" in ks else None
print("staging key:", bool(SKEY))

alive = {("orders", "p"): True, ("checkout", "p"): True, ("cart", "p"): True, ("orders", "s"): True}
stop = False
def beat():
    while not stop:
        for (svc, env), on in list(alive.items()):
            if on and (env == "p" or SKEY):
                call("POST", "/api/process/heartbeat", {"serviceName": svc, "status": "UP"}, headers=KEY if env == "p" else SKEY)
        time.sleep(4)
threading.Thread(target=beat, daemon=True).start()
time.sleep(3)

def log(svc, msg, key=KEY):
    return call("POST", "/api/logs", {"serviceName": svc, "logLevel": 4, "rawMessage": msg}, headers=key)[0]

def incidents(state=None, env="prod", token=V):
    s, body, _ = call("GET", "/api/process/incidents" + (f"?state={state}" if state else ""), token=token, project=P, env=env)
    return body if s == 200 else []

def alerts(env="prod"):
    s, body, _ = call("GET", "/api/chat/messages?channel=team", token=V, project=P, env=env)
    return [m["event"] for m in body["messages"] if m["authorType"] == "SYSTEM" and m["event"]["type"] == "INCIDENT_OPENED"] if s == 200 else []

# --- first incident: orders, DB timeouts ---
for n in range(6):
    log("orders", f"Order {n} failed: DB timeout after {100 + n}ms")
first = wait_for(lambda: [i for i in incidents("OPEN") if i["serviceName"] == "orders"], 60)
check("first incident opened", bool(first), True)
IA = first[0]["id"] if first else None
check("first incident has no history", [e.get("seenBefore") for e in wait_for(alerts, 20) or []], [0])
check("new incident starts unreviewed", first[0]["reviewStatus"] if first else None, "UNREVIEWED")

check("viewer cannot review", call("PUT", f"/api/process/incidents/{IA}/review", {"status": "SOLVED"}, token=V, project=P)[0], 403)
check("too long text rejected", call("PUT", f"/api/process/incidents/{IA}/review", {"rootCause": "x" * 4001}, token=A, project=P)[0], 400)
s, rv, _ = call("PUT", f"/api/process/incidents/{IA}/review",
                {"status": "SOLVED", "rootCause": "DB connection pool exhausted", "resolution": "Raised the pool size from 10 to 50"}, token=A, project=P)
check("owner saves a review", (s, rv["reviewStatus"], rv["resolution"], rv["reviewedBy"] is not None), (200, "SOLVED", "Raised the pool size from 10 to 50", True))
s, rv2, _ = call("PUT", f"/api/process/incidents/{IA}/review", {"status": "SOLVED"}, token=A, project=P)
check("partial update keeps the texts", rv2["rootCause"], "DB connection pool exhausted")

closed = wait_for(lambda: [i for i in incidents("CLOSED") if i["id"] == IA], 150, 5)
check("first incident closed", bool(closed), True)

# --- same kind of error, different service, different values ---
for n in range(6):
    log("checkout", f"order {900 + n} failed: db timeout after {5000 + n}ms")
second = wait_for(lambda: [i for i in incidents("OPEN") if i["serviceName"] == "checkout"], 60)
check("second incident opened", bool(second), True)
IB = second[0]["id"] if second else None
ev = wait_for(lambda: [e for e in alerts() if e["serviceName"] == "checkout"], 20)
if ev:
    check("alert says seen before + last fix", (ev[0]["seenBefore"], ev[0]["lastFix"], ev[0]["lastFixIncidentId"]),
          (1, "Raised the pool size from 10 to 50", IA))
else:
    check("alert for second incident", None, "present")
s, sim, _ = call("GET", f"/api/process/incidents/{IB}/similar", token=V, project=P)
check("similar finds the first one", [x["id"] for x in sim] if s == 200 else s, [IA])

# --- unrelated error is not similar ---
for n in range(6):
    log("cart", f"Coupon {n} expired")
third = wait_for(lambda: [i for i in incidents("OPEN") if i["serviceName"] == "cart"], 60)
IC = third[0]["id"] if third else None
s, sim, _ = call("GET", f"/api/process/incidents/{IC}/similar", token=V, project=P)
check("unrelated error has no similar incidents", sim if s == 200 else s, [])

# --- other environment ---
if SKEY:
    for n in range(6):
        log("orders", f"Order {n} failed: DB timeout after {n}ms", key=SKEY)
    st = wait_for(lambda: [i for i in incidents("OPEN", env="staging") if i["serviceName"] == "orders"], 60)
    ISt = st[0]["id"] if st else None
    s, sim, _ = call("GET", f"/api/process/incidents/{ISt}/similar", token=V, project=P, env="staging")
    check("similar crosses environments, reviewed first", [x["id"] for x in sim][:1] if s == 200 else s, [IA])

# --- library search ---
def lib(qs, env="prod"):
    s, body, _ = call("GET", "/api/process/incidents/library?" + qs, token=V, project=P, env=env)
    return [i["id"] for i in body] if s == 200 else s
check("search finds by fix text", lib("q=POOL+SIZE"), [IA])
check("search finds by error text", sorted(lib("q=db+timeout&allEnvironments=false")), sorted([IA, IB]))
check("filter by status", lib("status=SOLVED"), [IA])
check("unreviewed filter", IA not in lib("status=UNREVIEWED") and IB in lib("status=UNREVIEWED"), True)
if SKEY:
    check("all environments includes staging", ISt in lib("q=timeout"), True)
    check("this environment only excludes staging", ISt in lib("q=timeout&allEnvironments=false"), False)
check("regex characters are literal", lib("q=.*"), [])

# --- service down twice ---
alive[("cart", "p")] = False
d1 = wait_for(lambda: [i for i in incidents("OPEN") if i["kind"] == "SERVICE_DOWN"], 60)
alive[("cart", "p")] = True
wait_for(lambda: not [i for i in incidents("OPEN") if i["kind"] == "SERVICE_DOWN"], 60)
alive[("cart", "p")] = False
d2 = wait_for(lambda: [i for i in incidents("OPEN") if i["kind"] == "SERVICE_DOWN" and i["id"] != (d1[0]["id"] if d1 else "")], 60)
alive[("cart", "p")] = True
if d1 and d2:
    s, sim, _ = call("GET", f"/api/process/incidents/{d2[0]['id']}/similar", token=V, project=P)
    check("second DOWN of the same service finds the first", [x["id"] for x in sim], [d1[0]["id"]])
else:
    check("two DOWN incidents", (bool(d1), bool(d2)), (True, True))

finish(expected=21)
