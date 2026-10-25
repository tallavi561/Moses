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
  status: "UP" | "DEGRADED" | "DOWN" | "UNKNOWN";
  cpuPercent: number | null;
  memoryMb: number | null;
  calls: string[] | null;
  lastSeen: string;
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

export async function askAi(question: string): Promise<unknown> {
  const res = await fetch(`${API_BASE_URL}/api/ai/query`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
  if (!res.ok) throw new Error(`AI query failed: ${res.status}`);
  return res.json();
}
