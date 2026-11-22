"""API keys: the owner can show one again, others can't, revoking works."""
import json
import time

from support import call_json as call, check, finish, register

A, _ = register("Owner")
B, _ = register("Editor")
_, p = call("POST", "/api/projects", {"name": "K"}, token=A)
P = p["id"]
_, inv = call("POST", f"/api/projects/{P}/invites", token=A)
call("POST", f"/api/invites/{inv['token']}/accept", token=B)

_, created = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)
_, keys = call("GET", f"/api/projects/{P}/keys", token=A)
check("new key is revealable", keys[0]["revealable"], True)
check("list never contains the key", created["key"] in json.dumps(keys), False)
s, r = call("GET", f"/api/projects/{P}/keys/{created['id']}/reveal", token=A)
check("owner can show it again", (s, r["key"] == created["key"]), (200, True))
check("editor cannot", call("GET", f"/api/projects/{P}/keys/{created['id']}/reveal", token=B)[0], 403)
check("no token", call("GET", f"/api/projects/{P}/keys/{created['id']}/reveal")[0], 401)
check("unknown key -> 404", call("GET", f"/api/projects/{P}/keys/nope/reveal", token=A)[0], 404)
check("revealed key works for a heartbeat",
      call("POST", "/api/process/heartbeat", {"serviceName": "svc", "status": "UP"}, headers={"X-Api-Key": r["key"]})[0], 202)
call("DELETE", f"/api/projects/{P}/keys/{created['id']}", token=A)
check("revoked key: reveal 404", call("GET", f"/api/projects/{P}/keys/{created['id']}/reveal", token=A)[0], 404)
time.sleep(31)  # the gateway caches key lookups for 30 s (documented)
check("revoked key stops working within 30 s",
      call("POST", "/api/process/heartbeat", {"serviceName": "svc", "status": "UP"}, headers={"X-Api-Key": r["key"]})[0], 401)
finish(expected=9)
