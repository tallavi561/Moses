# MoSes Monitor - Product Vision

Companion to [ARCHITECTURE.md](ARCHITECTURE.md) (what exists and why) and
[project_specification.md](project_specification.md) (the search algorithm). This
document is about where the project is *heading*: the long-term differentiation, what
each piece of it means concretely, and - critically - what's built today vs. what's
still just a plan. Nothing in this file is a commitment to build it next; it exists so
near-term decisions don't accidentally foreclose it. Last updated: 2026-11-06.

## 1. The core idea

Most observability tools specialize in one signal: Splunk/ELK for logs, Datadog/Jaeger
for distributed tracing, Prometheus/Grafana for metrics, PagerDuty for alerting/on-call
workflow. Teams stitch several of these together and still end up manually correlating
"the checkout service got slow at 14:02" (metrics) with "here's the actual request that
was slow" (tracing) with "here's what it logged while handling it" (logs) with "is this
even a service we knew about, and what does it normally talk to" (topology).

**MoSes's differentiation is collapsing that correlation work into one system**: logs,
live service topology, distributed traces, resource usage, and (long-term) network-level
observability, all in one data model, searched by one custom algorithm, read by one AI
layer that can point at the *combination* of signals that explains a problem - not just
one of them in isolation.

## 2. The six signal pillars

| Pillar | What it captures | Status |
|---|---|---|
| **Logs** | Free-text events a service emits (errors, request lines, state changes) | **Built** - custom inverted-index search, `POST /api/logs` |
| **Topology & health** | Which services exist, how they connect, are they up | **Built** - Node/Connection model, heartbeats, URL prober |
| **Distributed tracing** | Which services a single request passed through, and how long each hop took | **Planned** - see §4 |
| **Resource usage** | CPU/RAM/disk per service over time | **Not started** - would piggyback on the heartbeat channel (`cpuPercent`/`memoryMb` fields already exist on the Node model but nothing populates them yet) |
| **Network-level observability** | Raw traffic volume and (optionally) message content between services, captured without the service's cooperation | **Long-term, infra-dependent** - see §5 |
| **Code/repo understanding** | AI reads a connected repo to infer/propose the topology above, rather than requiring it to be declared | **Planned**, discussed earlier as the "Repo+AI flow discovery" phase, not started |

Every pillar lands in the same system so the AI/analysis layer (§6) can reason across
all of them for one service at one point in time, instead of a human manually opening
five different dashboards.

## 3. The SKILL - what it covers today, and where it grows

The downloadable SKILL (`frontend/public/moses-monitoring-skill.md`) is the mechanism
for pillars 1 and 2 today: instructions an AI coding assistant follows to wire a
customer's own codebase into MoSes, without us ever touching their code ourselves.

**What it covers right now:**
- Picking a stable `serviceName` per process.
- Sending a heartbeat (`POST /api/process/heartbeat`) on startup and periodically, with
  `status`, `description`, and `connections` (so the topology graph builds itself).
- Registering a `probeUrl` instead, when a service already exposes a health endpoint -
  MoSes polls it directly rather than needing periodic heartbeats.
- Sending logs (`POST /api/logs`) for anything worth recording, as a logging hook/
  middleware rather than scattered calls.

**Where it's planned to grow (each is a separate, later piece of work):**
- **A published client library** (see the logger-library decision saved in memory):
  buffered, async, batched sending so the SKILL stops asking the AI to hand-write raw
  HTTP calls in application code, and the customer gets reliable delivery without
  blocking their own request path. HTTP as the default transport, UDP as an opt-in for
  extreme-throughput cases.
- **A required log schema/fields** the SKILL asks for - not just "send logs," but logs
  structured enough (status codes, durations, correlation fields) to actually support
  useful analysis later, rather than arbitrary free text.
- **Trace/span correlation** - see §4.
- **Resource metrics** - the SKILL could ask the generated code to sample and report
  `cpuPercent`/`memoryMb` alongside heartbeats, using whatever's idiomatic for that
  runtime (e.g. `process.cpuUsage()` in Node, `/proc` on Linux, a language's runtime
  metrics API).

## 4. Distributed tracing: the cheap step available now

Right now, a `LogEntry` has no field correlating it with other log entries from the same
logical request as it crosses services - there's no way to say "these five log lines
across three services all happened because of the same user action."

The industry-standard way to do this is **OpenTelemetry (OTel)**: every request gets a
`traceId` (one ID for the whole request's journey) and each service hop gets its own
`spanId` (plus the `spanId` of whoever called it, forming a tree). Adopting OTel for
real - its SDKs, its wire protocol (OTLP), its semantic conventions - is a substantial
piece of work (our backend would need an OTLP-compatible receiver, and the SKILL would
need to tell the AI to add OTel auto-instrumentation rather than our own simple HTTP
calls).

**The cheap, decided first step** (not yet implemented - open to do next): add optional
`traceId`/`spanId` fields to `LogEntry` and to the heartbeat payload now, before there's
real production data to migrate. This doesn't require adopting the OTel protocol itself
- our own proprietary log/heartbeat format can carry these IDs - but it means:
- The SKILL can start asking the AI to generate/propagate a correlation ID per request
  (most frameworks make this easy - e.g. middleware that generates one at the edge and
  threads it through downstream calls).
- The Search page can later group/filter by `traceId` to show "everything that happened
  for this one request," across services, without building real OTel support yet.
- If/when real OTel support is added later, these fields are already there to receive
  OTel-sourced IDs too - no schema migration needed at that point.

## 5. Network-level observability (eBPF) - the long-term, infra-gated piece

Everything in §3-4 depends on the monitored service *cooperating* (running code that
calls us). **eBPF** is categorically different: a privileged program loaded into the
Linux kernel of the host the service runs on, which observes network traffic passing
through that host *without* the service's code needing to do anything - closer to "a
camera on the network cable" than "the service calling home."

This is powerful (it sees services that were never wired up with the SKILL at all, and
can inspect actual message content between services, with full-capture only kicking in
once an anomaly is already suspected, to keep the steady-state cost cheap) but it comes
with a hard constraint discussed and confirmed: **it requires kernel-level privileges on
the host**, which most managed/PaaS platforms (Render among them - the platform MoSes
itself is deployed on) deliberately do not grant tenants, as a security boundary between
customers sharing the same infrastructure. It would only be usable by customers running
on infrastructure they administer themselves (their own VMs, their own Kubernetes
cluster, bare metal) - a narrower, more technically sophisticated slice of customers than
anyone who can run `npm install` and apply a SKILL.

**Conclusion carried forward from that discussion**: this is a real long-term
differentiator worth keeping on the roadmap, but it is not a near-term build - it needs
its own dedicated agent binary (built and maintained by MoSes, not generated per
customer), its own deployment story (a DaemonSet/systemd service the customer installs),
and a deliberate decision about which customer segment it's for, before any engineering
starts on it.

## 6. From signals to insight: metrics + AI

Collecting all six pillars is necessary but not sufficient - the differentiating claim is
that MoSes's AI layer can look across them together and explain *why* something is wrong,
not just surface raw numbers. Today's `ai-service` is an explicit placeholder
(`NaiveNlQueryTranslator` - rule-based keyword extraction from a natural-language
question into a structured search, not real inference). Growing it toward the vision
means, roughly, in order of how much groundwork each needs:

1. **Natural-language search over what's already collected** - closest to done; mostly
   needs a real LLM behind `ai-service` instead of the rule-based stub, operating on
   data pillars 1-2 which already exist.
2. **Anomaly detection** - noticing a service is behaving unusually (error rate, latency,
   restart frequency) without a human asking a question first. Needs pillars 3-4
   (tracing/metrics) to be meaningful beyond "it stopped sending heartbeats."
3. **Root-cause correlation** - given an anomaly, automatically pulling the relevant
   logs + trace + topology neighbors + recent metric shift into one explanation, instead
   of a human manually cross-referencing dashboards. This is the point where "all
   signals in one system" (§1) actually pays off versus a human doing the same
   correlation by hand across five tools.

## 7. From insight to action: ChatOps and auto-remediation

Two capabilities layered on top of §6, deliberately listed last because they depend on
everything above being trustworthy first:

- **ChatOps**: a chat interface where the system proactively surfaces a diagnosed problem
  to the team ("auth-service error rate is up 10x since the last deploy, likely cause:
  X") and lets them discuss/ask follow-ups, rather than the team having to go look.
  Needs an event/notification layer that doesn't exist yet (today's UI is pull-based -
  the user opens the Architecture/Search pages; nothing pushes to them).
- **Auto-remediation**: the system proposing (and, with approval, executing) a fix - e.g.
  restarting a stuck service, rolling back a bad deploy, scaling a resource-starved one.
  This needs a new concept that doesn't exist in the data model at all yet: a
  "remediation action" with a proposal/approval/audit-log lifecycle, plus whatever
  integration is needed to actually perform the action (deploy platform API access,
  infra credentials) - which is also a trust and security question, not just an
  engineering one, since it means MoSes would hold real production-changing permissions.

## 8. Reading order / what this implies for near-term decisions

This file exists so that near-term, concrete work (what actually gets built next) can be
checked against it without re-deriving the whole vision each time. The practical
takeaways already acted on or decided:

- Keep the Node/Connection data model able to carry more optional fields over time
  (resource metrics, trace IDs) rather than assuming logs/heartbeats are the only signal
  types it will ever hold - it already does this reasonably well.
- Don't build eBPF infrastructure now; it's real but gated on a customer segment and an
  agent binary that don't exist yet.
- Do add `traceId`/`spanId` as optional fields soon, while there's no production data to
  migrate - cheap now, expensive later.
- The future logger library should default to HTTP + async/batched delivery, not UDP by
  default (see the memory note from that discussion).
