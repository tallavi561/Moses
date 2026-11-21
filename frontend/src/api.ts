import { getSession, setSession, type Session, type SessionUser } from "./auth/session";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

/** A failed API call, carrying the HTTP status so screens can show the right message. */
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number
  ) {
    super(message);
  }
}

/**
 * The project and environment the user is working in. Every monitoring request (logs, search,
 * architecture, AI) carries it, and the gateway refuses one that names a project the user is
 * not in. Set by ProjectsProvider; null while no project is open.
 */
let apiScope: { projectId: string; environment: string } | null = null;

export function setApiScope(scope: { projectId: string; environment: string } | null) {
  apiScope = scope;
}

/**
 * fetch() plus the login token and the current scope. A 401 on a request that carried a token means the session is
 * no longer valid (expired, or the account is gone), so it ends the session and the app falls
 * back to the sign-in screen.
 */
async function apiFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const session = getSession();
  const headers = new Headers(init.headers);
  if (session) headers.set("Authorization", `Bearer ${session.token}`);
  if (apiScope) {
    headers.set("X-Project-Id", apiScope.projectId);
    headers.set("X-Environment", apiScope.environment);
  }

  const res = await fetch(url, { ...init, headers });
  if (res.status === 401 && session && getSession()?.token === session.token) {
    setSession(null);
  }
  return res;
}

async function postJson(path: string, body?: unknown): Promise<Response> {
  return apiFetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function expectOk(res: Response, what: string): Promise<Response> {
  if (!res.ok) throw new ApiError(`${what}: ${res.status}`, res.status);
  return res;
}

// ---- accounts ----

export async function login(email: string, password: string): Promise<Session> {
  return (await expectOk(await postJson("/api/auth/login", { email, password }), "Sign-in failed")).json();
}

export async function register(name: string, email: string, password: string): Promise<Session> {
  return (await expectOk(await postJson("/api/auth/register", { name, email, password }), "Sign-up failed")).json();
}

export async function fetchMe(): Promise<SessionUser> {
  return (await expectOk(await apiFetch(`${API_BASE_URL}/api/auth/me`), "Session check failed")).json();
}

// ---- projects and their teams ----

/** OWNER manages the team, environments and keys; EDITOR changes monitoring data; VIEWER only reads. */
export type ProjectRole = "OWNER" | "EDITOR" | "VIEWER";

/** The roles an owner can give someone else. */
export type AssignableRole = Exclude<ProjectRole, "OWNER">;

export interface Project {
  id: string;
  name: string;
  role: ProjectRole;
  memberCount: number;
  /** Never empty: a project always has at least one environment. */
  environments: string[];
  /** https://github.com/<owner>/<repo>, read by the chat agent; null until the owner sets it. */
  repoUrl: string | null;
}

/** A key a monitored service reports in with. The key itself is only ever returned on creation. */
export interface ApiKeyInfo {
  id: string;
  environment: string;
  prefix: string;
  createdAt: string;
  /** The owner can show this key again (false for keys created before that was possible). */
  revealable: boolean;
}

export interface CreatedApiKey extends ApiKeyInfo {
  key: string;
}

export interface ProjectMember {
  userId: string;
  name: string;
  email: string;
  role: ProjectRole;
  joinedAt: string;
}

export interface ProjectInvite {
  token: string;
  expiresAt: string;
}

export interface InvitePreview {
  projectName: string;
  expiresAt: string;
}

export async function fetchProjects(): Promise<Project[]> {
  return (await expectOk(await apiFetch(`${API_BASE_URL}/api/projects`), "Failed to load projects")).json();
}

export async function createProject(name: string): Promise<Project> {
  return (await expectOk(await postJson("/api/projects", { name }), "Failed to create project")).json();
}

/** Owner only. An empty string disconnects the repository. */
export async function setProjectRepo(projectId: string, repoUrl: string): Promise<Project> {
  const res = await apiFetch(`${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/repo`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ repoUrl }),
  });
  return (await expectOk(res, "Failed to save the repository")).json();
}

export async function fetchMembers(projectId: string): Promise<ProjectMember[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/members`);
  return (await expectOk(res, "Failed to load members")).json();
}

/** Adds an already-registered user by email; 404 means nobody has signed up with it. */
export async function addMember(projectId: string, email: string, role: AssignableRole): Promise<ProjectMember[]> {
  const res = await postJson(`/api/projects/${encodeURIComponent(projectId)}/members`, { email, role });
  return (await expectOk(res, "Failed to add member")).json();
}

export async function changeMemberRole(
  projectId: string,
  userId: string,
  role: AssignableRole
): Promise<ProjectMember[]> {
  const res = await apiFetch(
    `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ role }) }
  );
  return (await expectOk(res, "Failed to change role")).json();
}

export async function removeMember(projectId: string, userId: string): Promise<ProjectMember[]> {
  const res = await apiFetch(
    `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}`,
    { method: "DELETE" }
  );
  return (await expectOk(res, "Failed to remove member")).json();
}

/** Returns the project's environments after the change. 409: the name is taken. */
export async function addEnvironment(projectId: string, name: string): Promise<string[]> {
  const res = await postJson(`/api/projects/${encodeURIComponent(projectId)}/environments`, { name });
  return (await expectOk(res, "Failed to add environment")).json();
}

/** Also revokes the environment's keys. Its recorded data is not deleted, only unreachable. */
export async function removeEnvironment(projectId: string, name: string): Promise<string[]> {
  const res = await apiFetch(
    `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(name)}`,
    { method: "DELETE" }
  );
  return (await expectOk(res, "Failed to remove environment")).json();
}

export async function fetchApiKeys(projectId: string): Promise<ApiKeyInfo[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/keys`);
  return (await expectOk(res, "Failed to load keys")).json();
}

export async function createApiKey(projectId: string, environment: string): Promise<CreatedApiKey> {
  const res = await postJson(`/api/projects/${encodeURIComponent(projectId)}/keys`, { environment });
  return (await expectOk(res, "Failed to create key")).json();
}

/** Owner only: the key itself, to copy it into a service's configuration. */
export async function revealApiKey(projectId: string, keyId: string): Promise<string> {
  const res = await apiFetch(
    `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/keys/${encodeURIComponent(keyId)}/reveal`
  );
  return ((await (await expectOk(res, "Failed to show key")).json()) as { key: string }).key;
}

export async function revokeApiKey(projectId: string, keyId: string): Promise<ApiKeyInfo[]> {
  const res = await apiFetch(
    `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}/keys/${encodeURIComponent(keyId)}`,
    { method: "DELETE" }
  );
  return (await expectOk(res, "Failed to revoke key")).json();
}

export async function createInvite(projectId: string): Promise<ProjectInvite> {
  const res = await postJson(`/api/projects/${encodeURIComponent(projectId)}/invites`);
  return (await expectOk(res, "Failed to create invite")).json();
}

export async function fetchInvitePreview(token: string): Promise<InvitePreview> {
  const res = await apiFetch(`${API_BASE_URL}/api/invites/${encodeURIComponent(token)}`);
  return (await expectOk(res, "Invite lookup failed")).json();
}

export async function acceptInvite(token: string): Promise<Project> {
  const res = await postJson(`/api/invites/${encodeURIComponent(token)}/accept`);
  return (await expectOk(res, "Failed to join project")).json();
}

// ---- monitoring data ----

export interface LogEntry {
  logId: string;
  timestamp: string;
  serviceName: string;
  logLevel: number;
  ip: string;
  rawMessage: string;
  traceId: string | null;
  spanId: string | null;
}

export interface ProcessState {
  serviceName: string;
  processName: string | null;
  description: string | null;
  status: "UP" | "DEGRADED" | "DOWN" | "UNKNOWN";
  cpuPercent: number | null;
  memoryMb: number | null;
  /** Null when the service has never reported in the current environment. */
  lastSeen: string | null;
  probeUrl: string | null;
  lastProbeAt: string | null;
  lastProbeStatusCode: number | null;
  lastProbeLatencyMs: number | null;
  lastProbeError: string | null;
}

export type ConnectionDirection = "SOURCE_TO_TARGET" | "TARGET_TO_SOURCE" | "BIDIRECTIONAL";

export interface Connection {
  id: string;
  sourceService: string;
  targetService: string;
  direction: ConnectionDirection;
  communicationType: string | null;
  description: string | null;
}

export interface ConnectionPayload {
  sourceService: string;
  targetService: string;
  direction: ConnectionDirection;
  communicationType?: string;
  description?: string;
}

export async function searchLogs(
  words: string[],
  from: string,
  to: string,
  logLevel?: number,
  service?: string
): Promise<LogEntry[]> {
  const params = new URLSearchParams();
  words.forEach((w) => params.append("words", w));
  if (service) params.append("service", service);
  params.append("from", from);
  params.append("to", to);
  if (logLevel !== undefined) params.append("logLevel", String(logLevel));

  const res = await apiFetch(`${API_BASE_URL}/api/search?${params.toString()}`);
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

export interface LogPage {
  logs: LogEntry[];
  hasMore: boolean;
}

export interface LogPageParams {
  words: string[];
  from: string;
  to: string;
  services?: string[];
  before?: string;
  beforeId?: string;
  limit?: number;
}

/** One newest-first chunk of logs; pass the last log's timestamp/logId as before/beforeId for the next chunk. */
export async function searchLogPage(p: LogPageParams): Promise<LogPage> {
  const params = new URLSearchParams();
  p.words.forEach((w) => params.append("words", w));
  params.append("from", p.from);
  params.append("to", p.to);
  p.services?.forEach((s) => params.append("service", s));
  if (p.before) params.append("before", p.before);
  if (p.beforeId) params.append("beforeId", p.beforeId);
  if (p.limit) params.append("limit", String(p.limit));

  const res = await apiFetch(`${API_BASE_URL}/api/search/page?${params.toString()}`);
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

/** Every log of one trace across services, oldest first. */
export async function fetchTrace(traceId: string): Promise<LogEntry[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/search/trace/${encodeURIComponent(traceId)}`);
  if (!res.ok) throw new Error(`Trace lookup failed: ${res.status}`);
  return res.json();
}

export async function fetchProcessState(): Promise<ProcessState[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/state`);
  if (!res.ok) throw new Error(`Process state fetch failed: ${res.status}`);
  return res.json();
}

export interface HeartbeatPayload {
  serviceName: string;
  status: ProcessState["status"];
  processName?: string;
  description?: string;
  probeUrl?: string;
  connections?: string[];
}

export async function postHeartbeat(payload: HeartbeatPayload): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/heartbeat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to add service: ${res.status}`);
}

export interface NodeUpdatePayload {
  serviceName: string;
  status: ProcessState["status"];
  processName?: string;
  description?: string;
  probeUrl?: string;
  connections?: string[];
}

export async function updateNode(currentServiceName: string, payload: NodeUpdatePayload): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/nodes/${encodeURIComponent(currentServiceName)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to update service: ${res.status}`);
}

export async function deleteNode(serviceName: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/nodes/${encodeURIComponent(serviceName)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to delete service: ${res.status}`);
}

export async function fetchConnections(): Promise<Connection[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/connections`);
  if (!res.ok) throw new Error(`Failed to load connections: ${res.status}`);
  return res.json();
}

export async function createConnection(payload: ConnectionPayload): Promise<Connection> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/connections`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to create connection: ${res.status}`);
  return res.json();
}

export async function updateConnection(id: string, payload: ConnectionPayload): Promise<Connection> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/connections/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to update connection: ${res.status}`);
  return res.json();
}

export async function deleteConnection(id: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/connections/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to delete connection: ${res.status}`);
}

// ---- dashboards & metrics ----

export type DashboardScope = "ENVIRONMENT" | "SERVICE";

export interface DashboardView {
  id: string;
  scope: DashboardScope;
  serviceName: string | null;
  title: string;
  /** Services the chart shows; empty = every service in the environment. */
  services: string[];
  metric: DashboardMetric;
  minutesBack: number;
  /** PRIVATE: only its creator sees it. */
  visibility: DashboardVisibility;
  /** The signed-in user created it (only they can change its visibility). */
  mine: boolean;
  createdBy: string | null;
  createdAt: string;
}

export type DashboardMetric = "cpu" | "memory" | "errors";
export type DashboardVisibility = "SHARED" | "PRIVATE";

export interface DashboardUpdate {
  title?: string;
  services?: string[];
  metric?: DashboardMetric;
  minutesBack?: number;
  visibility?: DashboardVisibility;
}

/** What the AI did with a free-text edit: the saved dashboard, and a line or two for the user. */
export interface DashboardAiEdit {
  dashboard: DashboardView;
  changed: boolean;
  summary: string | null;
  note: string | null;
}

export interface DashboardPayload {
  scope: DashboardScope;
  serviceName?: string;
  title?: string;
  visibility?: DashboardVisibility;
}

export async function fetchDashboards(): Promise<DashboardView[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/dashboards`);
  if (!res.ok) throw new Error(`Failed to load dashboards: ${res.status}`);
  return res.json();
}

export async function createDashboard(payload: DashboardPayload): Promise<DashboardView> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/dashboards`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to create dashboard: ${res.status}`);
  return res.json();
}

export async function deleteDashboard(id: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/dashboards/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to delete dashboard: ${res.status}`);
}

export async function updateDashboard(id: string, update: DashboardUpdate): Promise<DashboardView> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/dashboards/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(update),
  });
  if (!res.ok) throw new Error(`Failed to save dashboard: ${res.status}`);
  return res.json();
}

/** Edits a dashboard from a free-text instruction, using the project's AI model. */
export async function editDashboardWithAi(id: string, instruction: string): Promise<DashboardAiEdit> {
  const res = await apiFetch(`${API_BASE_URL}/api/ai/dashboards/${encodeURIComponent(id)}/edit`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ instruction }),
  });
  if (!res.ok) {
    let message = `AI edit failed: ${res.status}`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // not JSON
    }
    throw new ApiError(message, res.status);
  }
  return res.json();
}

/** Logs one service sent recently (Monitoring access page). */
export interface ServiceLogCoverage {
  serviceName: string;
  logCount: number;
  withTraceId: number;
  lastLogAt: string | null;
}

export async function fetchLogCoverage(hours = 24): Promise<{ hours: number; services: ServiceLogCoverage[] }> {
  const res = await apiFetch(`${API_BASE_URL}/api/search/coverage?hours=${hours}`);
  if (!res.ok) throw new Error(`Failed to load log coverage: ${res.status}`);
  return res.json();
}

export interface MetricPoint {
  timestamp: string;
  cpuPercent: number | null;
  memoryMb: number | null;
  /** Only on error-rate points (fetchErrorRates): ERROR/FATAL logs per minute in the bucket. */
  errorsPerMinute?: number | null;
}

/** ERROR/FATAL logs per minute for every service, one point per bucket (zeros included). */
export async function fetchErrorRates(minutesBack?: number): Promise<ServiceMetrics[]> {
  const params = new URLSearchParams();
  if (minutesBack) params.append("minutesBack", String(minutesBack));
  const res = await apiFetch(`${API_BASE_URL}/api/process/metrics/errors?${params.toString()}`);
  if (!res.ok) throw new Error(`Failed to load error rates: ${res.status}`);
  return res.json();
}

// ---- incidents ----

export type IncidentKind = "ERROR_SPIKE" | "SERVICE_DOWN" | "FLOW_BROKEN";
export type IncidentState = "OPEN" | "CLOSED";

export interface IncidentErrorGroup {
  message: string;
  count: number;
  lastAt: string | null;
  traceId: string | null;
}

/** What the team concluded about an incident (the library part). */
export type IncidentReview = "UNREVIEWED" | "SOLVED" | "UNSOLVED" | "FALSE_ALARM";

export interface Incident {
  id: string;
  environment: string;
  serviceName: string;
  kind: IncidentKind;
  state: IncidentState;
  openedAt: string;
  closedAt: string | null;
  lastBreachAt: string | null;
  windowMinutes: number;
  currentErrors: number;
  peakErrors: number;
  baselineErrors: number;
  topErrors: IncidentErrorGroup[];
  /** FLOW_BROKEN only: serviceName is the flow's target. */
  flowId?: string | null;
  flowEntry?: string | null;
  flowRouteExample?: string | null;
  flowMaxSeconds?: number | null;
  checkedTraces: number;
  brokenTraces: number;
  peakBrokenTraces: number;
  brokenExamples: { traceId: string; stoppedAt: string; reason: "NOT_REACHED" | "TOO_SLOW" | "ERROR" }[];
  /** What the AI proposed when someone asked it to investigate (not the team's review). */
  aiInvestigation?: AiInvestigation | null;
  reviewStatus: IncidentReview;
  rootCause: string | null;
  resolution: string | null;
  /** User id of whoever wrote the review last. */
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export interface AiInvestigation {
  status: "RUNNING" | "DONE" | "FAILED";
  summary: string | null;
  rootCause: string | null;
  suggestedFix: string | null;
  confidence: "low" | "medium" | "high" | null;
  evidence: string[] | null;
  error: string | null;
  model: string | null;
  requestedBy: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export async function fetchIncident(id: string): Promise<Incident> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/incidents/${encodeURIComponent(id)}`);
  if (!res.ok) throw new Error(`Failed to load the incident: ${res.status}`);
  return res.json();
}

/** Thrown when the project has no AI model yet (409 "No AI model..."), so the UI can point at Settings. */
export class NoAiModelError extends Error {}

/** Starts an AI investigation of the incident; it runs in the background (poll fetchIncident). */
export async function investigateIncident(id: string, language: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/ai/incidents/${encodeURIComponent(id)}/investigate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ language }),
  });
  if (res.ok) return;
  const body = await res.json().catch(() => null);
  const message = body && typeof body.message === "string" ? body.message : `Failed to start the investigation: ${res.status}`;
  if (res.status === 409 && message.startsWith("No AI model")) throw new NoAiModelError(message);
  throw new Error(message);
}

/** Searches past incidents and what the team wrote about them (all environments by default). */
export async function fetchIncidentLibrary(filter: {
  q?: string;
  status?: IncidentReview;
  allEnvironments?: boolean;
}): Promise<Incident[]> {
  const params = new URLSearchParams();
  if (filter.q?.trim()) params.append("q", filter.q.trim());
  if (filter.status) params.append("status", filter.status);
  params.append("allEnvironments", String(filter.allEnvironments ?? true));
  const res = await apiFetch(`${API_BASE_URL}/api/process/incidents/library?${params.toString()}`);
  if (!res.ok) throw new Error(`Failed to search incidents: ${res.status}`);
  return res.json();
}

/** Earlier incidents like this one: same kind of error, or the same service going down. */
export async function fetchSimilarIncidents(id: string): Promise<Incident[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/incidents/${encodeURIComponent(id)}/similar`);
  if (!res.ok) throw new Error(`Failed to load similar incidents: ${res.status}`);
  return res.json();
}

export async function reviewIncident(
  id: string,
  review: { status?: IncidentReview; rootCause?: string; resolution?: string },
): Promise<Incident> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/incidents/${encodeURIComponent(id)}/review`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(review),
  });
  if (!res.ok) throw new Error(`Failed to save the review: ${res.status}`);
  return res.json();
}

export async function fetchIncidents(state?: IncidentState, limit = 100): Promise<Incident[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (state) params.append("state", state);
  const res = await apiFetch(`${API_BASE_URL}/api/process/incidents?${params.toString()}`);
  if (!res.ok) throw new Error(`Failed to load incidents: ${res.status}`);
  return res.json();
}

/** When a service's errors become an incident. `custom` is false while it uses the defaults. */
export interface AlertRule {
  serviceName: string;
  minErrors: number;
  factor: number;
  muted: boolean;
  custom: boolean;
  /** Minutes of errors counted per check. */
  windowMinutes: number;
}

export async function fetchAlertRules(): Promise<AlertRule[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/alert-rules`);
  if (!res.ok) throw new Error(`Failed to load alert rules: ${res.status}`);
  return res.json();
}

export async function saveAlertRule(
  serviceName: string,
  update: { minErrors?: number; factor?: number; muted?: boolean },
): Promise<AlertRule> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/alert-rules?service=${encodeURIComponent(serviceName)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(update),
  });
  if (!res.ok) throw new Error(`Failed to save alert rule: ${res.status}`);
  return res.json();
}

export async function resetAlertRule(serviceName: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/alert-rules?service=${encodeURIComponent(serviceName)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to reset alert rule: ${res.status}`);
}

export interface ServiceMetrics {
  serviceName: string;
  samples: MetricPoint[];
}

export async function fetchMetrics(services?: string[], minutesBack?: number): Promise<ServiceMetrics[]> {
  const params = new URLSearchParams();
  services?.forEach((s) => params.append("service", s));
  if (minutesBack) params.append("minutesBack", String(minutesBack));
  const res = await apiFetch(`${API_BASE_URL}/api/process/metrics?${params.toString()}`);
  if (!res.ok) throw new Error(`Failed to load metrics: ${res.status}`);
  return res.json();
}

/** How the AI service understood a free-text question. */
export interface AiInterpretation {
  words: string[];
  from: string;
  to: string;
  logLevel: number | null;
  service: string | null;
  traceId: string | null;
  /** "llm", or "naive" when the rule-based fallback was used. */
  translator: "llm" | "naive";
  fallbackReason: string | null;
}

export interface AiQueryResponse {
  interpretedAs: AiInterpretation;
  /** Newest first. */
  results: LogEntry[];
  hasMore: boolean;
}

export async function askAi(question: string): Promise<AiQueryResponse> {
  const res = await apiFetch(`${API_BASE_URL}/api/ai/query`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
  if (!res.ok) throw new Error(`AI query failed: ${res.status}`);
  return res.json();
}

// ---- team chat ----

/** "team" (everyone in the project) or the signed-in user's private channel with the agent. */
export type ChatChannel = string;

/** SYSTEM: MoSes itself (incident alerts), not a person or the AI agent. */
export type ChatAuthorType = "USER" | "AGENT" | "SYSTEM";

/** What a SYSTEM message is about; the chat renders it in the reader's language. */
export interface ChatSystemEvent {
  type: "INCIDENT_OPENED" | "INCIDENT_RESOLVED";
  incidentId: string;
  serviceName: string;
  incidentKind: IncidentKind;
  errors: number | null;
  baseline: number | null;
  windowMinutes: number | null;
  topError: string | null;
  durationSeconds: number | null;
  /** INCIDENT_OPENED: how many earlier incidents looked like this one, and the last fix found. */
  seenBefore?: number | null;
  lastFix?: string | null;
  lastFixIncidentId?: string | null;
  /** FLOW_BROKEN only. serviceName is where requests should have arrived. */
  flowEntry?: string | null;
  checkedTraces?: number | null;
  brokenTraces?: number | null;
  stoppedAt?: string | null;
  flowMaxSeconds?: number | null;
}

export interface ChatReplyPreview {
  id: string;
  authorName: string;
  authorType: ChatAuthorType;
  text: string;
}

export interface ChatMessage {
  id: string;
  projectId: string;
  channel: ChatChannel;
  authorType: ChatAuthorType;
  authorId: string;
  authorName: string;
  text: string;
  replyTo: ChatReplyPreview | null;
  /** The agent could not answer; the text says why. */
  failed: boolean;
  /** Only on SYSTEM messages. */
  event?: ChatSystemEvent | null;
  createdAt: string;
}

export interface ChatPage {
  /** Oldest first. */
  messages: ChatMessage[];
  hasMore: boolean;
}

export interface ChatIdentity {
  userId: string;
  privateChannel: ChatChannel;
  projectId: string;
}

export async function fetchChatIdentity(): Promise<ChatIdentity> {
  return (await expectOk(await apiFetch(`${API_BASE_URL}/api/chat/me`), "Chat unavailable")).json();
}

export async function fetchChatMessages(channel: ChatChannel, before?: string): Promise<ChatPage> {
  const params = new URLSearchParams({ channel, limit: "50" });
  if (before) params.set("before", before);
  const res = await apiFetch(`${API_BASE_URL}/api/chat/messages?${params.toString()}`);
  return (await expectOk(res, "Failed to load messages")).json();
}

export async function sendChatMessage(channel: ChatChannel, text: string, replyToId?: string): Promise<ChatMessage> {
  const res = await postJson("/api/chat/messages", { channel, text, replyToId });
  return (await expectOk(res, "Failed to send")).json();
}

// ---- the AI model a project's agent uses ----

export type LlmProvider = "anthropic" | "openai-compatible";

/** What members see of a model setting - never the key itself, only its last 4 characters. */
export interface LlmView {
  provider: LlmProvider;
  model: string;
  baseUrl: string | null;
  keyHint: string;
  updatedAt: string;
}

export interface LlmOverview {
  /** The default for every environment; null until the owner sets one. */
  project: LlmView | null;
  /** Per environment: its own setting, or null when it uses the project default. */
  environments: Record<string, LlmView | null>;
  /** False when the server has no encryption key configured and cannot store keys. */
  canStoreKeys: boolean;
}

export interface LlmInput {
  provider: LlmProvider;
  model: string;
  /** Empty keeps the stored key (to change only the model). */
  apiKey: string;
  baseUrl?: string;
}

function llmPath(projectId: string, environment?: string) {
  const base = `${API_BASE_URL}/api/projects/${encodeURIComponent(projectId)}`;
  return environment ? `${base}/environments/${encodeURIComponent(environment)}/llm` : `${base}/llm`;
}

/** The provider's reason (e.g. "Anthropic rejected this API key.") when it refused the key. */
async function llmResult(res: Response, what: string): Promise<LlmOverview> {
  if (!res.ok) {
    let message = `${what}: ${res.status}`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // not JSON
    }
    throw new ApiError(message, res.status);
  }
  return res.json();
}

export async function fetchLlmOverview(projectId: string): Promise<LlmOverview> {
  return llmResult(await apiFetch(llmPath(projectId)), "Failed to load the AI model");
}

/** Owner only. `environment` set = that environment's own model; otherwise the project default. */
export async function saveLlm(projectId: string, input: LlmInput, environment?: string): Promise<LlmOverview> {
  const res = await apiFetch(llmPath(projectId, environment), {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  return llmResult(res, "Failed to save the AI model");
}

/** Owner only. For an environment: back to the project default. */
export async function clearLlm(projectId: string, environment?: string): Promise<LlmOverview> {
  return llmResult(await apiFetch(llmPath(projectId, environment), { method: "DELETE" }), "Failed to remove the AI model");
}

// ---- request flows (trace checks) ----

export type TraceFlowStatus = "SUGGESTED" | "ACTIVE" | "PAUSED" | "REJECTED";

/** "Requests entering at entryService (of this route) should reach targetService within maxSeconds." */
export interface TraceFlow {
  id: string;
  entryService: string;
  /** null = every request entering there. */
  route: string | null;
  routeExample: string | null;
  targetService: string;
  maxSeconds: number;
  maxBrokenPercent: number;
  minTraces: number;
  status: TraceFlowStatus;
  createdBy: string | null;
  learnedSamples: number | null;
  learnedReachPercent: number | null;
  learnedP95Seconds: number | null;
  exampleTraceId: string | null;
  lastCheckedAt: string | null;
  lastCheckedTraces: number | null;
  lastBrokenTraces: number | null;
}

export async function fetchFlows(): Promise<TraceFlow[]> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/flows`);
  if (!res.ok) throw new Error(`Failed to load flows: ${res.status}`);
  return res.json();
}

export async function createFlow(flow: {
  entryService: string;
  targetService: string;
  routeExample?: string;
  maxSeconds?: number;
}): Promise<TraceFlow> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/flows`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(flow),
  });
  if (!res.ok) throw new Error(`Failed to add the flow: ${res.status}`);
  return res.json();
}

export async function updateFlow(
  id: string,
  update: { status?: Exclude<TraceFlowStatus, "SUGGESTED">; maxSeconds?: number; maxBrokenPercent?: number },
): Promise<TraceFlow> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/flows/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(update),
  });
  if (!res.ok) throw new Error(`Failed to update the flow: ${res.status}`);
  return res.json();
}

export async function deleteFlow(id: string): Promise<void> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/flows/${encodeURIComponent(id)}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`Failed to delete the flow: ${res.status}`);
}

/** Looks for new suggestions now instead of waiting for the next learning run. */
export async function learnFlows(): Promise<{ added: number; minSamples: number; flows: TraceFlow[] }> {
  const res = await apiFetch(`${API_BASE_URL}/api/process/flows/learn`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to look for flows: ${res.status}`);
  return res.json();
}
