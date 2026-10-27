const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export interface LogEntry {
  logId: string;
  timestamp: string;
  serviceName: string;
  logLevel: number;
  ip: string;
  rawMessage: string;
}

export interface ProcessState {
  serviceName: string;
  processName: string | null;
  description: string | null;
  status: "UP" | "DEGRADED" | "DOWN" | "UNKNOWN";
  cpuPercent: number | null;
  memoryMb: number | null;
  lastSeen: string;
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
  logLevel?: number
): Promise<LogEntry[]> {
  const params = new URLSearchParams();
  words.forEach((w) => params.append("words", w));
  params.append("from", from);
  params.append("to", to);
  if (logLevel !== undefined) params.append("logLevel", String(logLevel));

  const res = await fetch(`${API_BASE_URL}/api/search?${params.toString()}`);
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

export async function fetchProcessState(): Promise<ProcessState[]> {
  const res = await fetch(`${API_BASE_URL}/api/process/state`);
  if (!res.ok) throw new Error(`Process state fetch failed: ${res.status}`);
  return res.json();
}

export interface HeartbeatPayload {
  serviceName: string;
  status: ProcessState["status"];
  processName?: string;
  description?: string;
  connections?: string[];
}

export async function postHeartbeat(payload: HeartbeatPayload): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/process/heartbeat`, {
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
  connections?: string[];
}

export async function updateNode(currentServiceName: string, payload: NodeUpdatePayload): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/process/nodes/${encodeURIComponent(currentServiceName)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to update service: ${res.status}`);
}

export async function deleteNode(serviceName: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/process/nodes/${encodeURIComponent(serviceName)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to delete service: ${res.status}`);
}

export async function fetchConnections(): Promise<Connection[]> {
  const res = await fetch(`${API_BASE_URL}/api/process/connections`);
  if (!res.ok) throw new Error(`Failed to load connections: ${res.status}`);
  return res.json();
}

export async function createConnection(payload: ConnectionPayload): Promise<Connection> {
  const res = await fetch(`${API_BASE_URL}/api/process/connections`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to create connection: ${res.status}`);
  return res.json();
}

export async function updateConnection(id: string, payload: ConnectionPayload): Promise<Connection> {
  const res = await fetch(`${API_BASE_URL}/api/process/connections/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to update connection: ${res.status}`);
  return res.json();
}

export async function deleteConnection(id: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/process/connections/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Failed to delete connection: ${res.status}`);
}

export async function askAi(question: string): Promise<unknown> {
  const res = await fetch(`${API_BASE_URL}/api/ai/query`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
  if (!res.ok) throw new Error(`AI query failed: ${res.status}`);
  return res.json();
}
