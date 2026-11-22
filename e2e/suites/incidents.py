"""Incidents without AI: error spikes, services going down, alert rules and team-chat alerts."""
import threading
import time

from support import RUN, call, check, finish, register, url, wait_for

A, _ = register("Owner")
V, v_email = register("Viewer")
_, p, _ = call("POST", "/api/projects", {"name": "Incidents"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": v_email, "role": "VIEWER"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
KEY = {"X-Api-Key": k["key"]}

alive = {"orders": True, "payments": True, "inventory": True}
stop = False
def beat():
    while not stop:
        for svc, on in list(alive.items()):
            if on:
                call("POST", "/api/process/heartbeat", {"serviceName": svc, "status": "UP"}, headers=KEY)
        time.sleep(4)
threading.Thread(target=beat, daemon=True).start()
time.sleep(3)

def log(svc, level, msg, trace=None):
    return call("POST", "/api/logs", {"serviceName": svc, "logLevel": level, "rawMessage": msg, "traceId": trace}, headers=KEY)[0]

# --- rules API + permissions ---
s, rules, _ = call("GET", "/api/process/alert-rules", token=A, project=P)
by = {r["serviceName"]: r for r in rules}
check("rules list every service with defaults", (s, by.get("orders", {}).get("minErrors"), by.get("orders", {}).get("factor"), by.get("orders", {}).get("custom")), (200, 5, 3.0, False))
check("viewer cannot change a rule", call("PUT", "/api/process/alert-rules?service=payments", {"muted": True}, token=V, project=P)[0], 403)
check("bad factor rejected", call("PUT", "/api/process/alert-rules?service=payments", {"factor": 0.5}, token=A, project=P)[0], 400)
s, r, _ = call("PUT", "/api/process/alert-rules?service=payments", {"muted": True}, token=A, project=P)
check("owner mutes payments", (s, r["muted"], r["custom"]), (200, True, True))

# --- error spike on orders (8 of one kind + 1 other), payments muted with 6 errors, 2 info logs ---
for n in range(8):
    log("orders", 4, f"Order {1000 + n} failed: timeout after {3000 + n}ms", trace=f"trace-{RUN}-{n}")
log("orders", 5, "Database connection refused")
log("orders", 2, "Order 1 created")
for n in range(6):
    log("payments", 4, f"card {n} declined")

def open_incidents():
    s, body, _ = call("GET", "/api/process/incidents?state=OPEN", token=V, project=P)
    return body if s == 200 and body else None

found = wait_for(open_incidents, 60)
check("spike opened an incident", bool(found), True)
inc = [i for i in (found or []) if i["serviceName"] == "orders"]
check("exactly one incident, for orders (muted payments raised none)", [(i["serviceName"], i["kind"]) for i in (found or [])], [("orders", "ERROR_SPIKE")])
if inc:
    i = inc[0]
    check("counts the 9 errors, not the info log", i["currentErrors"], 9)
    check("same error with different values grouped", (i["topErrors"][0]["count"], i["topErrors"][1]["count"]), (8, 1))
    check("a trace id kept for the group", i["topErrors"][0]["traceId"] is not None, True)

def team_alerts():
    s, body, _ = call("GET", "/api/chat/messages?channel=team", token=V, project=P)
    return [m for m in body["messages"] if m["authorType"] == "SYSTEM"] if s == 200 else []

alerts = wait_for(team_alerts, 20)
check("alert posted in team chat", [(m["event"]["type"], m["event"]["serviceName"], m["event"]["errors"]) for m in alerts or []],
      [("INCIDENT_OPENED", "orders", 9)])
if alerts and inc:
    check("alert links to the incident", alerts[0]["event"]["incidentId"], inc[0]["id"])
time.sleep(8)
check("no duplicate incident or alert on the next checks", (len(open_incidents() or []), len(team_alerts())), (1, 1))

# --- error-rate series ---
s, series, _ = call("GET", "/api/process/metrics/errors?minutesBack=15", token=V, project=P)
rates = {x["serviceName"]: x["samples"] for x in series} if s == 200 else {}
check("error rate for orders sums to 9", round(sum(pt["errorsPerMinute"] for pt in rates.get("orders", []))), 9)
check("zero buckets present (15 points for 15 minutes)", len(rates.get("inventory", [])), 15)
_, d, _ = call("POST", "/api/process/dashboards", {"scope": "ENVIRONMENT", "title": "errs"}, token=A, project=P)
check("dashboard accepts the errors metric", call("PATCH", f"/api/process/dashboards/{d['id']}", {"metric": "errors"}, token=A, project=P)[1]["metric"], "errors")

# --- internal endpoint is internal ---
check("chat system endpoint needs the internal token", call("POST", "/internal/chat/system", {"projectId": P, "environment": "prod", "text": "x"}, base=url("chat"))[0], 403)
check("gateway does not route /internal", call("POST", "/internal/chat/system", {"projectId": P, "environment": "prod", "text": "x"}, token=A, project=P)[0] in (401, 403, 404), True)

# --- service down + recovery ---
alive["inventory"] = False
def down_incident():
    return [i for i in (open_incidents() or []) if i["kind"] == "SERVICE_DOWN"]
d_inc = wait_for(down_incident, 90)
check("silent service opens a SERVICE_DOWN incident", [(i["serviceName"]) for i in d_inc or []], ["inventory"])
alive["inventory"] = True
closed_down = wait_for(lambda: not down_incident(), 60)
check("heartbeat again closes it", bool(closed_down), True)

# --- spike closes itself one quiet window later ---
def orders_closed():
    s, body, _ = call("GET", "/api/process/incidents?state=CLOSED", token=V, project=P)
    return [i for i in body if i["serviceName"] == "orders" and i["kind"] == "ERROR_SPIKE"] if s == 200 else None
oc = wait_for(orders_closed, 150, 5)
check("spike closed after a quiet window", bool(oc), True)
types = [(m["event"]["type"], m["event"]["serviceName"], m["event"]["incidentKind"]) for m in team_alerts()]
check("chat has open+resolve for both incidents", sorted(types), sorted([
    ("INCIDENT_OPENED", "orders", "ERROR_SPIKE"), ("INCIDENT_RESOLVED", "orders", "ERROR_SPIKE"),
    ("INCIDENT_OPENED", "inventory", "SERVICE_DOWN"), ("INCIDENT_RESOLVED", "inventory", "SERVICE_DOWN")]))

s, _, _ = call("DELETE", "/api/process/alert-rules?service=payments", token=A, project=P)
_, rules, _ = call("GET", "/api/process/alert-rules", token=A, project=P)
check("reset brings back the defaults", ({r["serviceName"]: r for r in rules}["payments"]["custom"]), False)

finish(expected=22)
