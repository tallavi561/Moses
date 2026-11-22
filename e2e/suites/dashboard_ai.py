"""Free-text dashboard edits: the prompt, validation of the model's answer, saving."""
from support import RUN, call_json as call, check, finish, last_prompt, register, use_fake_model

INSTR = "רק CPU של checkout בשעתיים"
MODEL_KEY = f"fake-dashboard-{RUN}"

A, _ = register("Owner")
_, p = call("POST", "/api/projects", {"name": "L"}, token=A); P = p["id"]
_, k = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
for svc in ("checkout", "payments"):
    call("POST", "/api/process/heartbeat", {"serviceName": svc, "status": "UP"}, headers={"X-Api-Key": k["key"]})
s, _, _ = use_fake_model(P, A, MODEL_KEY)
check("fake model accepted in settings", s, 200)
_, d = call("POST", "/api/process/dashboards", {"scope": "ENVIRONMENT"}, token=A, project=P)
call("PATCH", f"/api/process/dashboards/{d['id']}", {"metric": "memory", "minutesBack": 1440}, token=A, project=P)

s, r = call("POST", f"/api/ai/dashboards/{d['id']}/edit", {"instruction": INSTR}, token=A, project=P)
check("edit applied", (s, r["changed"], r["dashboard"]["services"], r["dashboard"]["metric"], r["dashboard"]["minutesBack"]),
      (200, True, ["checkout"], "cpu", 60))
check("summary passed through", r["summary"], "Showing CPU for checkout over the last hour.")
check("unknown service reported", "ghost" in (r["note"] or ""), True)
_, saved = call("GET", f"/api/process/dashboards/{d['id']}", token=A, project=P)
check("saved in process-monitor", (saved["services"], saved["metric"], saved["minutesBack"]), (["checkout"], "cpu", 60))

last = last_prompt(MODEL_KEY)
system = last["messages"][0]["content"]
check("prompt has current settings", ("memory" in system, "1440" in system), (True, True))
check("prompt has this environment's services", ("checkout" in system and "payments" in system), True)
check("instruction sent as the user message", last["messages"][-1]["content"], INSTR)

s, r = call("POST", f"/api/ai/dashboards/{d['id']}/edit", {"instruction": "same again"}, token=A, project=P)
check("repeat = nothing to change", (s, r["changed"]), (200, False))
check("staging has no model -> 409",
      call("POST", f"/api/ai/dashboards/{d['id']}/edit", {"instruction": "x"}, token=A, project=P, env="staging")[0] in (404, 409), True)
finish(expected=10)
