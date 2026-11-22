"""The shared metrics cache: one copy per environment and range, subsets served from it."""
import time

from support import call, check, finish, register

A, _ = register("Owner")
B, b_email = register("Member")
_, p, _ = call("POST", "/api/projects", {"name": "Metrics"}, token=A)
P = p["id"]
call("POST", f"/api/projects/{P}/members", {"email": b_email, "role": "EDITOR"}, token=A)
_, k, _ = call("POST", f"/api/projects/{P}/keys", {"environment": "prod"}, token=A)


def heartbeat(service, cpu):
    return call("POST", "/api/process/heartbeat", {"serviceName": service, "status": "UP", "cpuPercent": cpu, "memoryMb": 100},
                headers={"X-Api-Key": k["key"]})[0]


def metrics(query, token=A):
    return {s["serviceName"]: s["samples"] for s in call("GET", f"/api/process/metrics?{query}", token=token, project=P)[1]}


check("heartbeats accepted", (heartbeat("orders", 10), heartbeat("payments", 20)), (202, 202))
time.sleep(1)
first = metrics("minutesBack=60")
check("every service is in the first read", sorted(first), ["orders", "payments"])
check("a subset matches the full copy", metrics("minutesBack=60&service=payments"), {"payments": first["payments"]})
heartbeat("orders", 90)
time.sleep(1)
check("a repeat read comes from the cache", metrics("minutesBack=60"), first)
check("another member shares the same copy", metrics("minutesBack=60", B), first)
check("another range is loaded fresh", len(metrics("minutesBack=360")["orders"]), len(first["orders"]) + 1)

finish(expected=6)
