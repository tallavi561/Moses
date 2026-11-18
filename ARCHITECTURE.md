# MoSes Monitor - Architecture & Decision Log

Living document. Captures what the system is, why it's built this way, and which
decisions are settled vs. still open. Last updated: 2026-11-14.

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

Seven independently deployable units (the diagram below predates `auth-service`, which sits
behind the gateway like the others and owns its own collections - see §9), all in one monorepo (multi-module Maven for the
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
| `ai-service` | NL query → structured search-service call; anomaly detection; summarization | **Partly built** - NL→query via a provider-agnostic LLM (rule-based fallback without a key); anomaly-check and summarize endpoints return 501 Not Implemented |
| `api-gateway` | Single public entry point, reverse proxy, CORS, and the sign-in check on every request (§9) | **Fully implemented** |
| `auth-service` | User accounts, login tokens, projects with their members and invite links | **Implemented** (email + password; see §9 for what is deliberately not there yet) |
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
  free-text question into `{words, service, logLevel, traceId, from, to}` and calls
  `search-service`'s `/api/search/page` over HTTP (newest first, 200 max, `hasMore`).
  The translator is `LlmQueryTranslator`: a language model reads the question plus
  the list of known service names (never log content), and its JSON reply is
  validated and clamped (unknown service dropped, level 0-5, window <= 14 days,
  <= 8 words). The model is **provider-agnostic and chosen purely by config** via the
  one-method `LlmClient` interface: `AnthropicLlmClient` (official SDK) and
  `OpenAiCompatibleLlmClient` (OpenAI, OpenRouter, Groq, Together, Gemini compat,
  Ollama/vLLM...). Env: `LLM_PROVIDER` (`anthropic` | `openai-compatible` | `none`),
  `LLM_MODEL`, `LLM_API_KEY`, `LLM_BASE_URL`. With no provider/key, or on any LLM
  failure, it falls back to the old rule-based `NaiveNlQueryTranslator`, and the
  response says so (`interpretedAs.translator` / `fallbackReason`) - the UI shows
  how the question was understood either way.
- **Anomaly detection** and **summarization**: endpoints exist and return
  `501 Not Implemented` on purpose - no detection rules or summarization approach
  have been designed yet. Placeholder > fabricated behavior.

## 5. Process/flow monitoring (skeleton)

`process-monitor-service` exposes:
- `POST /api/process/heartbeat` - a service/process reports its status (`UP` /
  `DEGRADED` / `DOWN` / `UNKNOWN`), optional CPU/memory, and which other services
  it `calls` (used to draw flow-diagram edges).
- `GET /api/process/state` - current snapshot of the nodes of one environment of one
  project (§10), consumed by the React flow view (`reactflow`).

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

## 9. Accounts, sign-in and projects (added 2026-11-14)

Built as the prerequisite for the team chat: a chat needs to know who wrote what, and who
belongs to which team.

**How it works**
- `auth-service` owns three collections nobody else touches: `users` (email unique and stored
  lowercased, bcrypt password hash), `projects` (name + embedded member list with a role per
  member, see §10) and `project_invites` (the invite token is the id; a TTL index
  deletes it at `expires_at`, 7 days by default).
- Sign-up / sign-in answer with a signed token (JWT, HS256, 7 days by default). The frontend
  keeps it in `localStorage` and sends it as `Authorization: Bearer ...` on every request
  (`frontend/src/api.ts`); a 401 on a request that carried a token signs the user out.
- `api-gateway` verifies the token itself with the shared `JWT_SECRET` (no call to
  `auth-service` per request) and answers 401 otherwise. Open without a token:
  `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/invites/{token}` (so the join
  page can name the project). `POST /api/logs` and `POST /api/process/heartbeat`, which
  monitored services call, are no longer open: they need an API key (§10).
- `auth-service` verifies the token again on its own endpoints rather than trusting a header
  from the gateway, because every backend also has its own public URL on Render.
- Any user can create a project and becomes its owner. People join in two ways: the owner adds
  an already-registered user by email, or shares an invite link (`/join/<token>`) that anyone
  who signs in or signs up can use until it expires.

**Config**: `JWT_SECRET` (32+ characters, the same value on `auth-service` and `api-gateway`;
both refuse to start without it - there is no default, so a deployment can never run on a
known key), `JWT_TTL_HOURS`, `INVITE_TTL_DAYS`, and `AUTH_SERVICE_URL` on the gateway.
`scripts/start-local.ps1` sets a development-only `JWT_SECRET` when none is set.

**Why a bearer token in `localStorage` and not a cookie**: the frontend and the gateway are
two different `*.onrender.com` sites, and `onrender.com` is a public suffix, so a session
cookie would be a third-party cookie, which browsers increasingly block. The cost is the
usual one: a script injected into the page could read the token.

**Deliberately not there yet**
- **Google and GitHub sign-in - wanted by the owner, planned next to email + password.**
  `users.password_hash` is already nullable for accounts that will have no password.
- The other backends (`search-service`, `process-monitor-service`, `ai-service`,
  `ingestion-service`) still answer anyone who calls their own public Render URL directly;
  only the gateway enforces sign-in. Closing that means each backend checking the token (or
  a gateway-only shared key) too.
- No password reset, email verification, sign-in rate limiting, token revocation, or
  leaving a project by yourself (the owner can remove a member, §10).

**A gateway bug this surfaced (fixed)**: the proxy's `RestTemplate` used the default
`HttpURLConnection`, which cannot relay a downstream 401 on a request with a body - a wrong
password became a 500 after the 75-second cold-start retry loop. The gateway now uses the JDK
`HttpClient` (`JdkClientHttpRequestFactory`).

## 10. Who owns the data: projects, environments, roles, API keys (added 2026-11-15)

Until this, monitoring data had no owner: every signed-in user saw every service and log.

**The model - three terms, no more** (changed 2026-11-18: environments are now a level, like
namespaces in OpenShift, not a tag)
- **Project**: the product being monitored, its team, and the boundary of permissions.
- **Environment**: where the services run (`prod`, `staging`, ...). Each environment is its own
  space with its own copy of everything below. A project starts with `prod`; the owner adds and
  removes others.
- **Service**: one running component in one environment; a node in that environment's diagram.

What belongs to what:

| Data | Belongs to | Stored as |
|---|---|---|
| Service definition (name, process name, description) | project + environment | `services`, unique `(project_id, environment, name)` |
| Connections | project + environment | `connections.project_id` + `.environment` |
| Service state (status, CPU/memory, last seen, probe URL and results) | project + environment | `process_state`, id `projectId\|environment\|serviceName` |
| Logs | project + environment | `logs.project_id`, `logs.environment` |
| Word index | project + environment | `index_buckets.scope` = `projectId\|environment`, unique `(scope, word, bucket)` |
| Chat (team channel and each person's private channel with the agent) | project + environment | `chat_messages.project_id` + `.environment` |
| AI model + key | project (default), optionally overridden per environment | `projects.llm`, `projects.environment_llm.<env>` |

So `payments-api` in prod and in staging are two separate nodes; adding, renaming or deleting a
service in one environment leaves the others alone. Services and connections recorded before
this change have no environment and are simply no longer shown (the owner chose to re-register
them rather than migrate).

**Roles** (per project, on the member entry): `OWNER` manages members, environments and keys;
`EDITOR` adds and edits services and connections; `VIEWER` only reads (and may ask the AI).
The project's creator is its one owner; the owner hands out `EDITOR` / `VIEWER`, changes them
and removes members. People who join by link or by email start as `EDITOR` unless the owner
picks otherwise. `MEMBER`, stored before roles existed, is read as `EDITOR`.

**API keys - how incoming data gets an owner.** A monitored service sends
`X-Api-Key: msk_...`. A key belongs to one environment of one project, so it alone says where
a log or heartbeat goes. The owner creates keys under Settings; a key is shown once, and only
its SHA-256 is stored (`api_keys`, the hash is the id). A key can do exactly two things:
`POST /api/logs` and `POST /api/process/heartbeat`. Removing an environment revokes its keys.

**Where it is enforced: `api-gateway`** (`AuthInterceptor`, `ScopeResolver`). For every
monitoring path (`/api/logs`, `/api/search`, `/api/process`, `/api/ai`) the gateway establishes
a scope before proxying:
- with `X-Api-Key`: asks `auth-service` (`POST /api/keys/resolve`, a path the gateway does not
  proxy) which project and environment the key belongs to;
- with a login token: the frontend names the scope in `X-Project-Id` / `X-Environment`, and the
  gateway asks `auth-service` (`GET /api/projects/{id}/access`) for the user's role there. Not
  a member, or no such environment: 404. A `VIEWER` changing something: 403.

It then passes the verified scope downstream as `X-Moses-Project-Id` / `X-Moses-Environment`,
after stripping any such header the caller sent. The backends filter every query by those
headers and answer 400 without them; `ai-service` forwards them to `search-service` and
`process-monitor-service`. Answers from `auth-service` are cached in the gateway for 30
seconds, so a role change, a removed member or a revoked key takes up to that long to apply.

**Frontend**: `ProjectsContext` tracks the current environment per project (remembered in
`localStorage`) and hands the pair to `api.ts`, which adds the two headers to every request.
The environment switcher is in the top bar; pages remount when the project or environment
changes. Settings has the team (roles, removal) and "Environments and keys".

**Data from before this change** has no project and is therefore invisible: old `logs` and
`index_buckets` expire by retention; old `process_state` and `connections` documents just stay
in the database. The old global unique index `word_bucket_unique` is dropped at
`ingestion-service` startup, since it would reject two projects using the same word in the
same hour.

**Deliberately not there yet**
- The backends still trust `X-Moses-*` from anyone who reaches their own URL directly (same
  gap as §9: only the gateway enforces access). Now it matters more, since those headers
  choose whose data is returned - a gateway-only shared key on the backends closes it.
- Permissions per environment (e.g. only some members may see `prod`).
- More than one owner, transferring ownership, deleting a project.
- Deleting an environment's data when the environment is removed.
- An "organization" level above projects. Not needed until one company has many projects
  with a shared team; it can be added above projects without changing anything below.

## 11. Team chat and the AI agent (added 2026-11-18)

- **`chat-service`** (port 8087) stores messages (`chat_messages`) and pushes them live over a
  WebSocket (`/ws/chat`; the client signs in with its first message, so the token never goes in
  a URL). Sending and history are plain REST through api-gateway (`/api/chat/**`, scoped like
  monitoring data; a viewer may chat). Each environment has its own chat with two kinds of
  channel: `team` (everyone in the project) and `agent:<userId>` (one person and the agent).
  The same API serves the web app now and the mobile app later.
- **The agent** answers every message in a private channel, and in the team channel only when
  tagged `@moses` or replied to. chat-service hands the message plus the last 30 messages to
  `ai-service` (`POST /internal/agent/reply`), which runs a tool loop with the environment's
  model: read-only tools over the project's GitHub repository (a bare clone under
  `~/.moses/repos`, refreshed at most once a minute; public `https://github.com/...` URLs only,
  so a setting can never point it at a local folder) and over the environment's logs, traces and
  services. Tool results are treated as data, not instructions. A per-project daily cap
  (`AGENT_DAILY_REPLIES_PER_PROJECT`, default 200) guards the bill.
- **The model and key are configuration, set in the UI** (Settings > AI model): the owner sets a
  project default and may give any environment its own (also offered when creating an
  environment). The key is checked with the provider before saving, stored AES-256-GCM
  encrypted by auth-service (`SECRETS_ENCRYPTION_KEY`), never sent back to a browser (members
  see the last 4 characters), and read by ai-service per request over
  `GET /internal/projects/{id}/llm` (cached 30 s). There is no server-wide default key.
- **Internal calls** (`/internal/**`, never proxied by the gateway) require `INTERNAL_API_TOKEN`,
  shared by auth-service, ai-service and chat-service. `scripts/start-local.ps1` generates both
  local secrets once into `secrets/` (git-ignored).
- **Tool use is implemented for Anthropic only** so far; an OpenAI-compatible model can be saved
  and is used for search translation, but the agent says it can't use tools with it yet.
- **Not there yet**: proactive monitoring (the agent speaking up on its own), push
  notifications, the mobile app, private repositories, and the backends' own URLs still trust
  the scope headers (see section 10).
