# MoSes Monitor - Architecture & Decision Log

Living document. Captures what the system is, why it's built this way, and which
decisions are settled vs. still open. Last updated: 2026-11-10.

## 1. What this is

A self-hosted, AI-assisted monitoring system (Splunk-like) with three pillars:

1. **Log search** - a custom inverted-index search engine (not a third-party search
   engine), designed by the project owner. See [project_specification.md](project_specification.md)
   for the full algorithm spec.
2. **Process/flow monitoring** - tracking live process and service state, not just
   text search, with a visual flow diagram of the system.
3. **AI layer** - natural-language querying, anomaly detection, and summarization,
   built *on top of* the custom search algorithm (not a replacement for it).

Target deployment: [Render](https://render.com). Frontend: React.

## 2. Service topology (decided: full microservices from day one)

Six independently deployable units, all in one monorepo (multi-module Maven for the
Java services, a separate `frontend/` for React):

```
                         ┌───────────────────┐
                         │  React frontend    │  (Render Static Site)
                         └─────────┬──────────┘
                                   │ HTTPS
                         ┌─────────▼──────────┐
                         │    api-gateway      │  reverse proxy + CORS,
                         │  (public entry)     │  the ONLY public backend URL
                         └─────────┬──────────┘
            ┌──────────────┬───────┴───────┬──────────────┐
            ▼              ▼               ▼              ▼
   ┌─────────────────┐ ┌──────────┐ ┌──────────────────┐ ┌───────────┐
   │ ingestion-service│ │  search- │ │ process-monitor-  │ │ ai-service │
   │  (owns writes)   │ │ service  │ │     service       │ │(stateless) │
   └────────┬─────────┘ └────┬─────┘ └─────────┬─────────┘ └─────┬─────┘
            │ writes         │ reads           │ owns             │ calls
            ▼                ▼                 ▼                  ▼
   ┌──────────────────────────────────┐  ┌──────────────┐   search-service
   │  MongoDB: logs, index_buckets    │  │  MongoDB:     │   over HTTP only
   │  (shared cluster, shared schema) │  │ process_state │   (never touches
   └──────────────────────────────────┘  └──────────────┘    Mongo directly)
```

### Why this split (and the honest tradeoff)

**The recommendation given was a 3-service "modular monolith + AI" split**
(ingestion+search combined, AI separate, frontend separate) - fewer moving parts,
cheaper on Render, easier to debug. **The decision made was full microservices from
day one anyway.** That's a legitimate call if independent scaling/failure isolation
matters more than operational simplicity right now - but it comes with real costs
worth naming so they don't surprise later:

- **Render cost**: 5 backend services + 1 static site. Render's free tier spins
  down each web service after inactivity, so a cold request can cascade through
  multiple sleeping services (frontend → gateway → ingestion/search/process/ai),
  each paying its own cold-start. A paid plan (or at least upgrading the
  most-hit services) removes this.
- **No managed MongoDB on Render** - needs an external cluster (MongoDB Atlas free
  tier works for MVP). `MONGODB_URI` is shared between `ingestion-service` and
  `search-service` (see data ownership below); it's set manually per service in
  the Render dashboard, not committed to `render.yaml`.
- **Inter-service calls are plain HTTP via `RestTemplate`/the gateway's
  reverse-proxy**, not a message queue or service mesh. Fine at this scale; revisit
  if write throughput or fan-out gets heavy.
- **Data ownership is NOT fully isolated per service.** `ingestion-service` and
  `search-service` share the same two MongoDB collections (`logs`, `index_buckets`)
  by design - `search-service` must see what `ingestion-service` just wrote, and
  building that via events/replication instead would be real infrastructure for no
  real benefit at this stage. `ai-service` and `process-monitor-service` do NOT
  share a database with anything - `ai-service` is fully stateless and only calls
  `search-service`'s REST API; `process-monitor-service` owns its own collection
  (`process_state`) that nothing else touches directly.

### Service responsibilities

| Service | Owns | Status |
|---|---|---|
| `ingestion-service` | Writes to `logs` + `index_buckets`, tokenization, in-memory buffer + flush, TTL retention | **Fully implemented** |
| `search-service` | Read-only search algorithm (macro-filter → intersection → micro-filter) | **Fully implemented** |
| `process-monitor-service` | Process/service heartbeats, current topology for the flow diagram | **Skeleton** - heartbeat ingest + state read-back only, no history/alerting yet |
| `ai-service` | NL query → structured search-service call; anomaly detection; summarization | **Skeleton** - NL→query pipeline works end-to-end with a rule-based (non-LLM) translator; anomaly-check and summarize endpoints return 501 Not Implemented |
| `api-gateway` | Single public entry point, reverse proxy, CORS | **Fully implemented** |
| `frontend` | React UI: sidebar nav, search, flow diagram, settings | **Shell built per UX spec** (frontend/מסמך אפיון.docx) - collapsible sidebar, EN/HE i18n (English default, layout never mirrors to RTL - only text changes), dark-default theme with manual toggle. Dashboard/Uploads are "coming soon" stubs |

## 3. The search algorithm (owner-designed, see project_specification.md)

Inverted index with time-bucketed documents on MongoDB:
- `logs` collection: raw log + metadata, one doc per log.
- `index_buckets` collection: one doc per `(word, time-bucket)` pair, holding the
  `log_ids` that contain that word in that bucket. Keeps documents bounded in size
  (MongoDB's 16MB limit) and makes retention cheap (drop old buckets).

Search flow: pick buckets in the time range → fetch `log_ids` per word → intersect
(AND) → re-filter precisely by exact timestamp/level → fetch full docs from `logs`.

### Resolved open points (from the original spec)

| Open point | Decision |
|---|---|
| Tokenization | Full lowercase, split on whitespace only (keeps `java.lang.NullPointerException` as one token), **no** stop-word filtering |
| High-cardinality fields (IP/UUID/timestamps in text) | Regex-detected and replaced with a generic placeholder (`<ip>`, `<uuid>`, `<timestamp>`, `<num>`) *before* tokenizing, so they never bloat the index. Raw text is still fully preserved in `logs` for exact lookups |
| Write batching | In-memory buffer (`ConcurrentHashMap`), scheduled flush (default 5s) via idempotent `$addToSet` bulk upsert |
| Retention | TTL index, 14 days default, both on `logs.timestamp` and `index_buckets.bucket_start`, programmatically ensured at startup so it can change via config without a code change |

### Known limitations / things not taken as "finished" (raised during review, not blocking)

These were flagged as **honest critique, not bugs** - the MVP is correct, but these
are real gaps worth tracking rather than "discovering" again in six months:

1. **No selectivity ordering in AND queries.** `SearchService` intersects words in
   the order given, not rarest-first. For a common word over a wide time range this
   pulls a large `log_ids` set before narrowing. Classic inverted-index fix: query
   posting-list *sizes* cheaply first (e.g. via `$size` in an aggregation,
   avoiding pulling the full array over the wire), sort ascending, then fetch.
   **Not implemented - backlog item.**
2. **No phrase/proximity search.** AND of independent words ignores order and
   distance - "connection refused" also matches logs where the two words are far
   apart or reversed. Would need a positional index (store token offsets) for
   true phrase search. **Not needed for MVP; flag if requested.**
3. **IP/UUID aren't searchable via the text index** (by design - see placeholder
   masking above) **but structured fields should be exposed as first-class filter
   params.** `logs.ip` and `logs.service_name` are already stored as real fields;
   `search-service`'s API doesn't yet expose `ip=`/`serviceName=` as separate
   filter parameters combinable with free-text search. **Recommended next
   improvement to `SearchQuery`/`SearchController`.**
4. **Fixed 1-minute bucket granularity for all time ranges.** Fine for recent data;
   a 30-day search scans thousands of bucket docs. A tiered approach (fine buckets
   for the last ~48h, coarser hourly/daily buckets further back, built by a
   background compaction job) would scale much better long-term. **Not needed
   yet - flag if query latency becomes a problem on wide time ranges.**

## 4. AI layer (decided: NL query + anomaly detection + summarization, built *on top
   of* the owner's search algorithm only - no separate ML models at this stage)

- **NL query → search-service**: `ai-service`'s `NlQueryController` translates a
  free-text question into `{words, from, to, logLevel}` and calls
  `search-service`'s `/api/search` over HTTP. The current `NaiveNlQueryTranslator`
  is an explicit **placeholder** - a rule-based heuristic (stop-word stripping,
  "last 1 hour" default, literal error/warning keyword match), not real language
  understanding. It exists so the full pipeline (UI → ai-service → search-service
  → UI) could be built and tested before committing to an LLM integration (API key
  handling, which model, cost) - that's a decision for the user to make explicitly,
  not one to make silently inside a dependency.
- **Anomaly detection** and **summarization**: endpoints exist and return
  `501 Not Implemented` on purpose - no detection rules or summarization approach
  have been designed yet. Placeholder > fabricated behavior.

## 5. Process/flow monitoring (skeleton)

`process-monitor-service` exposes:
- `POST /api/process/heartbeat` - a service/process reports its status (`UP` /
  `DEGRADED` / `DOWN` / `UNKNOWN`), optional CPU/memory, and which other services
  it `calls` (used to draw flow-diagram edges).
- `GET /api/process/state` - current snapshot of all known nodes, consumed by the
  React flow view (`reactflow`).

This is intentionally minimal: no history, no alerting, no automatic
process-discovery agent yet. It proves the pipeline (heartbeat → state → flow
diagram) end to end; the real heartbeat-sending agent/sidecar for monitored
services doesn't exist yet.

## 6. Stack decisions

| Decision | Choice | Why |
|---|---|---|
| Backend language | Java 21 (Spring Boot 3.2) | Explicit requirement: real parallel processing. Started on Java 17 (no virtual threads available there), **upgraded to 21 once the JDK was installed**. `ingestion-service`'s executor now uses `Executors.newVirtualThreadPerTaskExecutor()` instead of a bounded platform-thread pool, and every service has `spring.threads.virtual.enabled: true` so Tomcat's own request-handling threads are virtual too. All Dockerfiles use `eclipse-temurin-21` base images. |
| Database | MongoDB | Dictated by the owner's search algorithm design (bucketed inverted index relies on Mongo's flexible document model + TTL indexes) |
| Frontend | React + TypeScript (Vite) | Explicit requirement |
| Flow diagram | `reactflow` | Handles node/edge layout, dragging, fit-to-view out of the box |
| Service split | Full microservices (6 units) | Explicit decision, see tradeoffs in §2 |
| Deployment | Render (Docker web services + 1 static site) | Explicit requirement |

### Toolchain gotchas hit and resolved during setup (worth keeping so they aren't rediscovered)

- `create-vite@9`'s default template now uses an experimental "rolldown-vite" build
  with platform-native optional dependencies, which hit a reproducible npm 10.8.2
  arborist bug (`Cannot read properties of null (reading 'edgesOut')`) on this
  machine. Fixed by pinning the stable toolchain instead: Vite 5, React 18,
  `@vitejs/plugin-react` 4, TypeScript 5.5 - all installed cleanly.
- `npm install` of anything in this environment needs `--legacy-peer-deps` or it
  hits the same arborist bug even on simple installs.
- A real bug was found and fixed during end-to-end browser testing: `api-gateway`'s
  reverse proxy used `RestTemplate.exchange(String url, ...)`, which re-encodes an
  already-percent-encoded query string (`%3A` → `%253A`), corrupting ISO timestamps
  sent by the browser and breaking `@RequestParam Instant` binding downstream with
  a 400. Fixed by building a `URI` via `URI.create(...)` instead of passing a raw
  string (see `ProxyController`). Also found: blindly relaying the downstream
  response's `Transfer-Encoding`/`Content-Length` headers corrupted response
  framing (`ERR_INCOMPLETE_CHUNKED_ENCODING` in the browser) - fixed by stripping
  hop-by-hop headers before re-sending the response.

## 7. What's verified vs. not

**Verified end-to-end** (both locally and on the live Render deployment - see
§8): ingest a log → search finds it (structured search), NL query → AI service
→ search-service → UI, process heartbeat → flow diagram renders live state. All
through `api-gateway`, matching the real deployed topology.

**Not yet done:**
- Real LLM integration for NL query understanding (currently a rule-based stub)
- Anomaly detection and summarization logic (currently 501 stubs)
- Real authentication/authorization and backend multi-tenancy (the frontend's
  login/projects UI is still a mock single-user, localStorage-backed shell -
  **not fine for a real multi-user deployment**, deliberately deferred)
- Repo+AI driven topology discovery (see VISION.md §2/§8)

## 8. Render deployment

Live at:
- Frontend: https://moses-monitor-frontend.onrender.com
- API gateway (only public backend URL): https://api-gateway-x17f.onrender.com

Deployed as a single Render **Blueprint** from `render.yaml` ("New → Blueprint",
pointed at this repo). All `fromService`-computed env vars (`*_SERVICE_URL` on
`api-gateway`) are overridden in the Render dashboard to each downstream
service's **public** `onrender.com` URL rather than Render's private-network
`host:port` form - see "Gotchas" below for why.

### MongoDB Atlas

No managed MongoDB on Render, so `MONGODB_URI` points at an external **MongoDB
Atlas M0 (free)** cluster, region `us-west-2` (AWS), database name
`moses_monitor`. One DB user (`myproject`) is shared by the three Mongo-backed
services (`ingestion-service`, `search-service`, `process-monitor-service`).
**The actual connection string/password lives only in the Render dashboard
and MongoDB Atlas - never in the repo.** Network Access is `0.0.0.0/0` (allow
from anywhere), since Render doesn't publish a fixed outbound IP on the free
tier.

A **Render Environment Group** is the planned next step for `MONGODB_URI`, so
changing the password means editing one place instead of three services -
not done yet as of this writing.

### Gotchas hit during the first deploy (read before touching this again)

1. **`fromService`/`hostport` private-network env vars aren't reliable here.**
   One service (`process-monitor-service`) consistently got a
   `java.net.UnknownHostException` from `api-gateway` over Render's private
   network, while its public `onrender.com` URL worked fine. Worked around by
   pointing all of `api-gateway`'s downstream `*_SERVICE_URL` env vars at the
   **public** URLs instead of relying on `fromService`/`hostport`. Also:
   `fromService`/`hostport` only resolves on Render's private network, which a
   **browser can never reach** - `VITE_API_BASE_URL` (a frontend build-time
   var) must always be a public URL, set manually (`sync: false`), never
   `fromService`.
2. **`VITE_API_BASE_URL` is baked in at *build* time**, not read at runtime -
   it's a static site. Setting/changing it requires an actual rebuild to take
   effect; if a deploy reuses a cached build layer, it silently keeps the old
   (or default `http://localhost:8080`) value. Fixed by using Render's
   **"Clear cache and deploy"**, not just a regular redeploy, whenever this
   variable changes or looks stale.
3. **MongoDB Atlas password changes aren't instant.** A new DB user password
   can take roughly a minute or two to propagate across all shard nodes of
   the replica set; testing immediately after a password change can still
   show `bad auth: authentication failed` even though the new password is
   correct. Wait a bit before redeploying/retesting after a password change.
4. **Never load-test or script-probe (`curl` in a loop, etc.) a live
   `*.onrender.com` URL.** Render's shared free-tier domains sit behind
   Cloudflare, which can flag a zone for a **Managed Challenge** after bursts
   of non-browser automated traffic - this can escalate to Cloudflare being
   unable to even reach the origin to serve the challenge page, affecting
   *all* visitors (not just the automated client), and didn't clear with
   simple waiting. The fix that worked: delete and recreate the entire
   Blueprint, which gives every service a fresh random `onrender.com`
   subdomain (clean Cloudflare reputation). Test manually, one request at a
   time, from a real browser.
5. **MongoDB Atlas free (M0) cluster creation can be blocked by region.** If
   the cluster-creation screen only offers paid tiers (M10/M30/Flex) with no
   M0 option, check whether the project already has one Free cluster (only
   one is allowed per project) before assuming M0 was discontinued - it's
   usually a prior cluster, possibly auto-paused from inactivity.
6. Free-tier web services spin down after ~15 minutes of inactivity and take
   30-50s to wake on the next request - normal, not a bug.
