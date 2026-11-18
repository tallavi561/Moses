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

export interface ChatReplyPreview {
  id: string;
  authorName: string;
  authorType: "USER" | "AGENT";
  text: string;
}

export interface ChatMessage {
  id: string;
  projectId: string;
  channel: ChatChannel;
  authorType: "USER" | "AGENT";
  authorId: string;
  authorName: string;
  text: string;
  replyTo: ChatReplyPreview | null;
  /** The agent could not answer; the text says why. */
  failed: boolean;
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
