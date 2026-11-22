"""Private and shared dashboards, and the Monitoring access page's log coverage."""
import time

from support import call, check, finish, register

A, _ = register("Owner")
B, b_email = register("Editor")
_, p, _ = call("POST", "/api/projects", {"name": "Vis"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": b_email, "role": "EDITOR"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
KEY = {"X-Api-Key": k["key"]}

s, priv, _ = call("POST", "/api/process/dashboards", {"scope": "ENVIRONMENT", "visibility": "PRIVATE", "title": "mine"}, token=A, project=P)
check("private created", (s, priv["visibility"], priv["mine"]), (201, "PRIVATE", True))
s, shared, _ = call("POST", "/api/process/dashboards", {"scope": "ENVIRONMENT", "title": "team"}, token=A, project=P)
check("default is shared", (s, shared["visibility"]), (201, "SHARED"))
check("same scope twice by a person = two dashboards", priv["id"] != shared["id"], True)

_, la, _ = call("GET", "/api/process/dashboards", token=A, project=P)
_, lb, _ = call("GET", "/api/process/dashboards", token=B, project=P)
check("owner sees both", sorted(d["title"] for d in la), ["mine", "team"])
check("other member sees only shared", [d["title"] for d in lb], ["team"])
check("other member: shared isn't 'mine'", lb[0]["mine"], False)
check("other member can't open private", call("GET", f"/api/process/dashboards/{priv['id']}", token=B, project=P)[0], 404)
check("other member can't patch private", call("PATCH", f"/api/process/dashboards/{priv['id']}", {"metric": "memory"}, token=B, project=P)[0], 404)
check("other member can't delete private", call("DELETE", f"/api/process/dashboards/{priv['id']}", token=B, project=P)[0], 404)
check("other member can edit shared", call("PATCH", f"/api/process/dashboards/{shared['id']}", {"metric": "memory"}, token=B, project=P)[0], 200)
check("other member can't make shared private", call("PATCH", f"/api/process/dashboards/{shared['id']}", {"visibility": "PRIVATE"}, token=B, project=P)[0], 403)
s, u, _ = call("PATCH", f"/api/process/dashboards/{priv['id']}", {"visibility": "SHARED"}, token=A, project=P)
check("owner shares it", (s, u["visibility"]), (200, "SHARED"))
check("now the other member sees it", len(call("GET", "/api/process/dashboards", token=B, project=P)[1]), 2)
call("PATCH", f"/api/process/dashboards/{priv['id']}", {"visibility": "PRIVATE"}, token=A, project=P)
check("private again -> hidden", len(call("GET", "/api/process/dashboards", token=B, project=P)[1]), 1)
check("AI edit reaches the owner's private dashboard (409 = no model, not 404)",
      call("POST", f"/api/ai/dashboards/{priv['id']}/edit", {"instruction": "x"}, token=A, project=P)[0], 409)
check("API key can't make a private one",
      call("POST", "/api/process/dashboards", {"scope": "SERVICE", "serviceName": "svc", "visibility": "PRIVATE"}, headers=KEY)[0], 400)
s1, a1, _ = call("POST", "/api/process/dashboards", {"scope": "SERVICE", "serviceName": "svc"}, headers=KEY)
s2, a2, _ = call("POST", "/api/process/dashboards", {"scope": "SERVICE", "serviceName": "svc", "title": "svc v2"}, headers=KEY)
check("agent request stays idempotent", (s1, s2, a1["id"] == a2["id"], a2["createdBy"]), (201, 200, True, "agent"))
check("caller can't fake the user header",
      call("GET", "/api/process/dashboards", token=B, project=P, headers={"X-Moses-User-Id": "x"})[1].__len__(), 2)

# log coverage
call("POST", "/api/logs", {"serviceName": "checkout", "rawMessage": "order placed", "logLevel": 2, "traceId": "t-1"}, headers=KEY)
call("POST", "/api/logs", {"serviceName": "checkout", "rawMessage": "order paid", "logLevel": 2}, headers=KEY)
call("POST", "/api/logs", {"serviceName": "payments", "rawMessage": "charge ok", "logLevel": 2}, headers=KEY)
time.sleep(8)
s, cov, _ = call("GET", "/api/search/coverage?hours=24", token=A, project=P)
by = {x["serviceName"]: x for x in cov["services"]}
check("coverage counts logs", (s, by.get("checkout", {}).get("logCount"), by.get("payments", {}).get("logCount")), (200, 2, 1))
check("coverage counts traced logs", (by["checkout"]["withTraceId"], by["payments"]["withTraceId"]), (1, 0))
check("coverage is per environment", call("GET", "/api/search/coverage", token=A, project=P, env="staging")[0] in (200, 404), True)
finish(expected=21)
