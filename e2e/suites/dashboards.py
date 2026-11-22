"""Dashboards: settings, validation, environments, and what a viewer may do."""
from support import call, check, finish, register

A, _ = register("Owner")
V, v_email = register("Viewer")
_, p, _ = call("POST", "/api/projects", {"name": "Dash"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": v_email, "role": "VIEWER"}, token=A)
call("POST", f"/api/projects/{P}/environments", {"name": "staging"}, token=A)

s, d, _ = call("POST", "/api/process/dashboards", {"scope": "ENVIRONMENT"}, token=A, project=P)
D = d["id"]
check("new dashboard has default settings", (s, d["services"], d["metric"], d["minutesBack"]), (201, [], "cpu", 60))
s, sd, _ = call("POST", "/api/process/dashboards", {"scope": "SERVICE", "serviceName": "checkout"}, token=A, project=P)
check("service dashboard shows its service", sd["services"], ["checkout"])

s, u, _ = call("PATCH", f"/api/process/dashboards/{D}",
               {"metric": "memory", "minutesBack": 360, "services": ["a", "a", " b ", ""]}, token=A, project=P)
check("patch saves settings (deduped, trimmed)", (s, u["metric"], u["minutesBack"], u["services"]),
      (200, "memory", 360, ["a", "b"]))
s, u, _ = call("PATCH", f"/api/process/dashboards/{D}", {"title": "Prod memory"}, token=A, project=P)
check("partial patch keeps other fields", (u["title"], u["metric"], u["services"]), ("Prod memory", "memory", ["a", "b"]))
check("bad metric -> 400", call("PATCH", f"/api/process/dashboards/{D}", {"metric": "disk"}, token=A, project=P)[0], 400)
check("bad range -> 400", call("PATCH", f"/api/process/dashboards/{D}", {"minutesBack": 90}, token=A, project=P)[0], 400)
check("empty title -> 400", call("PATCH", f"/api/process/dashboards/{D}", {"title": "  "}, token=A, project=P)[0], 400)
s, g, _ = call("GET", f"/api/process/dashboards/{D}", token=A, project=P)
check("get by id", (s, g["title"], g["minutesBack"]), (200, "Prod memory", 360))
s, lst, _ = call("GET", "/api/process/dashboards", token=A, project=P)
check("list carries settings", [x["metric"] for x in lst if x["id"] == D], ["memory"])
check("other environment can't see it", call("GET", f"/api/process/dashboards/{D}", token=A, project=P, env="staging")[0], 404)
check("other environment can't patch it",
      call("PATCH", f"/api/process/dashboards/{D}", {"metric": "cpu"}, token=A, project=P, env="staging")[0], 404)

s, body, _ = call("POST", f"/api/ai/dashboards/{D}/edit", {"instruction": "only cpu please"}, token=A, project=P)
check("free-text edit without a model -> 409 with a message", (s, "Settings" in body.get("message", "")), (409, True))
check("free-text edit of unknown dashboard",
      call("POST", "/api/ai/dashboards/nope/edit", {"instruction": "x"}, token=A, project=P)[0], 409)
check("blank instruction -> 400",
      call("POST", f"/api/ai/dashboards/{D}/edit", {"instruction": ""}, token=A, project=P)[0], 400)

check("viewer can read", call("GET", f"/api/process/dashboards/{D}", token=V, project=P)[0], 200)
check("viewer can't patch", call("PATCH", f"/api/process/dashboards/{D}", {"metric": "cpu"}, token=V, project=P)[0], 403)
check("viewer can't free-text edit",
      call("POST", f"/api/ai/dashboards/{D}/edit", {"instruction": "x"}, token=V, project=P)[0], 403)
check("viewer can still ask questions", call("POST", "/api/ai/query", {"question": "errors"}, token=V, project=P)[0], 200)
check("no token -> 401", call("POST", f"/api/ai/dashboards/{D}/edit", {"instruction": "x"}, project=P)[0], 401)
finish(expected=19)
