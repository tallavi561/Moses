---
name: moses-monitoring-integration
description: Wire this codebase's services into MoSes Monitor - send real logs and live process status, per environment (prod, staging, ...) with an API key, so they show up in the Architecture page and are searchable, instead of being registered manually through the UI.
---

# MoSes Monitor integration

You are setting up a codebase to report into a MoSes Monitor instance: a log/process
monitoring system with a custom search index, a live architecture graph (nodes +
connections), and automatic health checks. Follow this document to wire the codebase's
services in. Everything here is plain HTTP - no SDK or client library to install, in any
language.

**Base URL** (the MoSes API gateway for this instance):

```
{{MOSES_BASE_URL}}
```

This is the gateway of the instance this skill was downloaded from. If the person running
this told you a different URL, use that instead. All paths below are relative to this base
URL. Read it from configuration (e.g. `MOSES_URL`) rather than hard-coding it. If it is a
`localhost` address, it is only reachable from the machine MoSes runs on - services deployed
elsewhere need the URL the person exposes MoSes at; ask them.

## How MoSes organises the data: project, service, environment

- **Project** - the product being monitored and its team. Everything you send lands in one
  project.
- **Service** - one running component of the project (one node in the diagram), identified
  by its `serviceName`.
- **Environment** - where the services run: `prod`, `staging`, `dev`, ... Every project
  starts with `prod`; the project owner adds others in MoSes. Environment names are
  lowercase letters, digits and `-`.

Each environment is its own space - like a namespace in OpenShift - with its own copy of
everything:

| Separate per environment |
|---|
| The services (`serviceName`, `description`, `processName`) - the nodes of the diagram |
| The connections between them |
| Status (`UP`/`DOWN`/...), CPU, memory, last seen, the `probeUrl` and its health-check results |
| Logs and search results |
| The team chat and the AI agent's view of the system |

So `payments-api` in prod and `payments-api` in staging are two separate nodes, each in its
own environment's diagram; people pick the environment under the project name in MoSes.
Consequences for you:

- Each environment registers its services and connections itself, with its own API key.
  A service that runs in prod and staging reports in both.
- Use **the same `serviceName` in every environment** where it is the same service, so people
  can compare them. Never encode the environment in it (`payments-api`, not
  `payments-api-staging`) - the API key already says which environment it is.
- Deleting or renaming a service in one environment does not touch the others.

## Authentication: one API key per environment

Every request below must carry an API key:

```
X-Api-Key: msk_...
```

A key belongs to **exactly one environment of one project**. The key alone decides where a
log or heartbeat goes - there is no `project` or `environment` field in the payloads, and
headers like `X-Project-Id` / `X-Environment` are ignored for key requests. To report into
staging instead of prod, use the staging key; nothing else changes.

- The project's owner creates keys in MoSes under **Settings -> Environments and keys**,
  choosing the environment for each. The owner can show or copy a key again later under Settings.
  **Ask the person running this for the key(s)** - one per environment the code is deployed
  to. Never invent one, and never reuse the prod key for another environment.
- Read the key from configuration, never hard-code or commit it. Recommended:
  `MOSES_API_KEY` (and `MOSES_URL`) as environment variables / secrets, set per deployment.
  Add them to the project's example config (`.env.example`, Helm values, etc.) with
  placeholder values, and make sure real values are git-ignored.
- **If `MOSES_API_KEY` is not set, disable MoSes reporting quietly** (log locally only).
  This keeps local development, tests and CI from failing or polluting real environments.
  If the team wants local runs in MoSes too, the owner can create a `dev` environment and a
  key for it.
- A key can do exactly two things: `POST /api/logs` and `POST /api/process/heartbeat`. It
  cannot read anything back.
- A key stops working when the owner revokes it or removes its environment (within about
  30 seconds). To rotate a key: the owner creates a new one, you deploy it, then the owner
  revokes the old one - both work at the same time during the switch.

**Responses to handle:**

| Status | Meaning | What the client should do |
|---|---|---|
| `202` | Accepted | - |
| `400` | Invalid body (e.g. missing `serviceName`, `rawMessage`, `status`, `logLevel`) | A bug in the payload - log it locally once, don't retry that payload |
| `401` | Missing, unknown or revoked key | Stop retrying; log one local warning that the MoSes key is invalid |
| `403` | The key was used for something other than sending logs/heartbeats | A bug - don't use the key for anything else |
| `502` / `503` / `504` / network error | MoSes temporarily unavailable | Retry later with backoff; drop the oldest queued items if the queue fills up |

## What to do

1. **Find the services/processes in this repo.** Each independently-run process (a web
   server, a worker, a cron job, a CLI daemon that stays up) is one "node" in MoSes.
   Pick a short, stable `serviceName` for each (e.g. `payments-api`, `email-worker`) -
   this name is the node's identity within the project, so keep it consistent across
   restarts and identical in every environment.

2. **Write (or update) a detailed README for each service**, at its root (e.g.
   `payments-api/README.md`). A reader landing on the repo - or on the service's node in
   MoSes - should understand what it is and how it fits in without reading its source.
   Cover, in this order:
   - **What it does** - a short paragraph: its purpose and responsibility, in plain
     language (not just "handles payments" - what requests/events it actually handles,
     what it owns).
   - **Its `serviceName`** - state the exact name you registered it under in MoSes, so the
     README and the Architecture page are never out of sync.
   - **Dependencies** - every database, queue, external API, and other internal service it
     calls, one line each. Match this list to the `connections` you send in its heartbeat;
     if they drift apart, fix whichever one is wrong.
   - **Who calls it** - the other services that call this one (the other direction of
     `connections`), if known.
   - **Configuration** - every environment variable it reads (including `MOSES_URL` /
     `MOSES_API_KEY`), with a one-line meaning for each and whether it's required.
   - **Running it locally** - the exact commands (install, build, start), and anything it
     needs running alongside it (a local database, another service, env vars to set).
   - **Monitoring** - one line noting it reports to MoSes (heartbeats and/or logs), and
     where to look (Architecture / Search / Dashboard pages) rather than duplicating that
     explanation.
   If a README already exists, don't rewrite what's already accurate - only add or fix
   what's missing or wrong, especially anything you just wired in (the `serviceName`,
   its connections, the MoSes config vars). Keep it current: when a later change to the
   service affects any of the sections above, update the README in the same change.

3. **Add the configuration**: `MOSES_URL` and `MOSES_API_KEY` for every service, read at
   startup, wired into each deployment environment the codebase has (look for existing
   per-environment config: `.env.*` files, Docker Compose, Kubernetes/Helm, CI deploy
   jobs, platform settings). Where you cannot set the real values, leave clearly marked
   placeholders and list them in your report.

4. **For each service, send a heartbeat on startup and then periodically** (every
   15-60 seconds is reasonable - pick an interval and set up a timer/scheduled task,
   whatever is idiomatic for this language/framework):

   ```
   POST /api/process/heartbeat
   Content-Type: application/json
   X-Api-Key: msk_...

   {
     "serviceName": "payments-api",
     "status": "UP",
     "description": "Handles checkout and refunds",
     "connections": ["user-db", "email-worker", "web-frontend"],
     "cpuPercent": 12.5,
     "memoryMb": 184
   }
   ```

   - `status` (required) is one of `UP`, `DEGRADED`, `DOWN`, `UNKNOWN`. Send `UP` as long
     as the process is healthy; if you can cheaply detect a degraded state (e.g. a
     dependency is failing but the service still partially works), send `DEGRADED`.
   - `description` is optional free text - one sentence on what the service does.
   - `processName` is optional: the OS process / executable name, if useful.
   - `connections` is optional, and it **replaces all of this service's edges** in the
     diagram, in both directions: any existing edge to a service not in the list is
     removed. So either list **every** service this one is connected to - the ones it
     calls *and* the ones that call it - or leave the field out entirely (then its edges
     are left as they are). Never send an empty or partial list by accident. Use exactly
     the `serviceName`s you picked for the other services. MoSes creates a default
     connection object for each new edge that people can later annotate in the UI.
   - `cpuPercent` and `memoryMb` are optional resource metrics - **include them in every
     heartbeat** so MoSes can show each service's load on the Architecture page. Sample
     them cheaply at heartbeat time, using whatever is idiomatic for the runtime:
     - `cpuPercent`: the process's CPU use over the last interval, as a percent of one
       core (0-100 for a single core; it may exceed 100 on multi-core). Compute it from
       the delta of the process's CPU time divided by the elapsed wall time (e.g.
       `process.cpuUsage()` in Node, `psutil.Process().cpu_percent()` in Python,
       `OperatingSystemMXBean.getProcessCpuLoad()` in Java, `runtime`/cgroup stats in Go).
     - `memoryMb`: the process's resident memory in megabytes (e.g.
       `process.memoryUsage().rss`, `psutil.Process().memory_info().rss`, JVM
       `Runtime.totalMemory() - freeMemory()`). Values must be numbers >= 0.
     If a number can't be read on some platform, omit that field rather than sending 0.
   - Any log received for a `serviceName` also counts as proof of life. If neither a
     heartbeat nor a log arrives for a few minutes (3 by default), MoSes marks the node
     DOWN in that environment automatically - so a heartbeat every 15-60 seconds keeps a
     quiet service UP.

   Alternative to heartbeats: if the service already exposes a health-check endpoint
   (e.g. `GET /health`), you can instead register it once with a `probeUrl` and skip the
   periodic POSTs - MoSes will poll that URL itself:

   ```
   POST /api/process/heartbeat
   Content-Type: application/json
   X-Api-Key: msk_...

   {
     "serviceName": "payments-api",
     "status": "UP",
     "probeUrl": "https://payments-api.internal.example.com/health",
     "connections": ["user-db", "email-worker", "web-frontend"]
   }
   ```

   - The probe URL belongs to the key's environment: register the staging URL with the
     staging key and the prod URL with the prod key.
   - One heartbeat with `probeUrl` is enough; MoSes then polls on its own schedule and
     sets `status` itself.
   - Every heartbeat sets the probe URL, so if a service sends **both** periodic
     heartbeats and wants a probe, include the same `probeUrl` in every heartbeat - a
     heartbeat without it clears the probe.
   - Use a probe only when the health endpoint is reachable from the MoSes instance (same
     network / public internet). Probe-only services have no CPU/memory metrics.

5. **Send logs** for anything you'd currently print/log to stdout or a log file -
   requests, errors, significant state changes:

   ```
   POST /api/logs
   Content-Type: application/json
   X-Api-Key: msk_...

   {
     "serviceName": "payments-api",
     "logLevel": 2,
     "ip": "10.0.4.12",
     "rawMessage": "POST /checkout 200 142ms",
     "timestamp": "2026-01-15T10:30:00Z"
   }
   ```

   - `serviceName`, `logLevel` and `rawMessage` are required. One log per request - there
     is no batch endpoint.
   - `logLevel` is a number: `0` TRACE, `1` DEBUG, `2` INFO, `3` WARN, `4` ERROR,
     `5` FATAL.
   - `ip` is optional (the client/request IP, when there is one).
   - `timestamp` is optional (ISO-8601 UTC); set it when the log is written, so queued logs
     keep their real time. If omitted, the server stamps receipt time.
   - Don't put the environment in the message or the `serviceName` - the key already
     records it, and the Search page filters by the environment chosen under the project name.
   - **All logging must go through one dedicated `Logger` class** (or the language's
     equivalent: a module, a wrapper around the existing logging library) that this
     codebase uses everywhere instead of `print`/`console.log`/`System.out`. That single
     class is the only place that talks to MoSes: it exposes `trace/debug/info/warn/error/
     fatal` methods, stamps `serviceName`, level and timestamp, reads `MOSES_URL` /
     `MOSES_API_KEY`, and POSTs to `/api/logs`. If the codebase already has a logging
     library, build the `Logger` as a thin wrapper/transport on top of it and replace
     direct `print`/`console` calls with it. Do not scatter raw `fetch`/`POST` calls
     through the business logic. The heartbeat sender should share its config and HTTP
     client.
   - The `Logger` must send asynchronously: calls only push onto a bounded in-memory queue,
     and a background worker drains it (sending several requests concurrently is fine). It
     must never block or throw into request handling. Always also write locally (stdout),
     so nothing is lost if MoSes is unreachable. Follow the response table above
     (back off on 5xx/network errors, stop on 401).
   - **Every incoming request must be logged through that `Logger`** (and therefore sent
     to MoSes): add one HTTP middleware/filter/interceptor per service that logs method,
     path, status code and duration (e.g. `POST /checkout 200 142ms`) at INFO, and 4xx/5xx
     at WARN/ERROR, including the client `ip`. Also log every outbound call this service
     makes to another service in `connections` (same format), so traffic between services
     shows up on both sides.
   - **Correlate every request with a `traceId`** so MoSes can show "everything that
     happened for this one request" across services. In the request-logging middleware:
     1. Read the incoming `X-Trace-Id` header; if it is missing, generate a new random
        id (a UUID, or 16+ hex chars) - this is the request's `traceId`.
     2. Generate a fresh short `spanId` (8+ hex chars) for this service's handling of the
        request, and read the caller's span from the incoming `X-Parent-Span-Id` header
        if present.
     3. Keep both in request-scoped context (async-local storage, `contextvars`,
        `ThreadLocal`/MDC, Go `context.Context`...) so the `Logger` can attach `traceId`
        and `spanId` automatically to every log written during that request - business
        code should not pass them around by hand.
     4. On every outbound call to another service, send `X-Trace-Id: <traceId>` and
        `X-Parent-Span-Id: <spanId>` headers. The downstream service's middleware
        (step 1) then continues the same trace.
     5. Put them in the log payload as optional `traceId` / `spanId` fields:
        `{"serviceName":"payments-api","logLevel":2,"rawMessage":"POST /checkout 200 142ms","traceId":"3f2a...","spanId":"9c1d..."}`.
     Logs outside any request (startup, background jobs) simply omit both fields. Don't
     put the traceId inside `rawMessage` - use the dedicated fields. A trace is only
     visible inside one environment, which is normal: a request doesn't cross from staging
     into prod.
   - Never send the `X-Api-Key` header to anything other than MoSes, and never log it.
   - Don't log secrets, full request/response bodies, or personal data in `rawMessage`.

6. **Double-check the `connections` lists are consistent.** Within an environment, every
   service that sends `connections` must list all of its neighbours (both directions) -
   otherwise heartbeats keep removing and re-adding each other's edges. Each environment
   has its own diagram, so lists may differ between environments when the deployments
   really differ (e.g. a service that only exists in staging). When in doubt, send
   `connections` only from the services whose neighbours you are sure of, and omit the
   field on the rest.

7. **Ask for the dashboards you think are worth watching.** A dashboard is a saved view on
   the Dashboard page: either the whole environment, or one specific service, each showing
   that scope's live status and a CPU/memory chart. You don't build the chart yourself -
   just declare which ones you want and MoSes creates them:

   ```
   POST /api/process/dashboards
   Content-Type: application/json
   X-Api-Key: msk_...

   {
     "scope": "SERVICE",
     "serviceName": "payments-api",
     "title": "payments-api"
   }
   ```

   - `scope` (required) is `"ENVIRONMENT"` (the whole environment this key belongs to - no
     `serviceName`) or `"SERVICE"` (one specific service - `serviceName` required, must be
     one of the `serviceName`s you're reporting under). There is no third scope: a
     "process" dashboard *is* a service dashboard here - a service's heartbeat already
     **is** its process state, so watching one process means watching one service.
   - `title` is optional; a sensible default is used if left out.
   - This call is **idempotent**: asking again for the same `(scope, serviceName)` updates
     the existing dashboard's title instead of creating a duplicate, so it's safe to run on
     every integration/deploy rather than tracking "did I already ask for this one".
   - A reasonable default: one `ENVIRONMENT` dashboard for the overall picture, plus one
     `SERVICE` dashboard for any service whose resource use is worth watching on its own
     (high traffic, known to be CPU/memory-heavy, or one you're actively tuning) - not
     necessarily every single service, which would just be noise.
   - This is the one other thing an API key may do beyond logs/heartbeats (see the response
     table above - the same status codes apply here, including a plain `400` if `scope` is
     missing or `serviceName` is missing for a `SERVICE` dashboard).
   - These are **recommendations, not decisions**: MoSes marks every dashboard you create
     this way as suggested by the integration agent (not written by the person), and the
     project's own people can delete any of them at any time, or create their own
     dashboards freely (any scope, any title, whenever they want) regardless of what you
     asked for here. Don't treat "ask for the dashboards you think are worth watching" as
     something that needs their approval first - just ask, and let them prune or add to it
     afterwards.

8. **Report back to the person running this** what you set up:
   - which services got heartbeats (and whether they report `cpuPercent`/`memoryMb`), and
     which got probe URLs instead;
   - where each service's README lives and what you wrote or changed in it;
   - where the `Logger` class lives, where the request-logging middleware was added, and
     how the `traceId` is propagated between services;
   - which dashboards you asked for and why - note that these are only suggestions, shown
     as such in MoSes, and the person is free to delete any of them or create their own;
   - which environments you wired, which config variables (`MOSES_URL`, `MOSES_API_KEY`)
     each deployment needs, and where they still have to put real keys - reminding them
     to create one key per environment under Settings -> Environments and keys;
   - how to verify: pick the environment under the project name in MoSes, then check the
     Architecture, Search and Dashboard pages;
   - that the project owner can connect this codebase's GitHub repository under
     Settings -> Repository, so the MoSes AI agent in the project chat can read the code
     alongside the logs and services you just wired up.

## What NOT to do

- Don't add a dependency/SDK for this - it's a few HTTP calls.
- Don't send heartbeats or logs from code that isn't actually part of a long-running
  service (e.g. one-off scripts, build tooling, tests).
- Don't hard-code or commit API keys, and don't share one key between environments.
- Don't put the environment name into `serviceName` - the same service must keep the same
  name everywhere.
- Don't invent a `serviceName` that doesn't match what you listed in another service's
  `connections` array - names must match exactly (case-sensitive) for edges to connect.
- Don't block application startup or request handling waiting on these HTTP calls - fire
  heartbeats/logs asynchronously and swallow (log locally, don't crash) failures, since
  MoSes being unreachable should never take the monitored service down with it.
