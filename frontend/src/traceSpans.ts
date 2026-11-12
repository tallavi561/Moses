import type { LogEntry } from "./api";

/**
 * One bar of the trace timeline. Logs are point events, so a span is derived: logs that share a
 * (service, spanId) are one span, and a log that reports how long the work took (a `durationMs`
 * field in its JSON payload, or "142ms" in its text) stretches it back from the log's timestamp,
 * because request logs are written when the request finishes.
 */
export interface TraceSpan {
  key: string;
  service: string;
  spanId: string | null;
  label: string;
  /** Epoch ms. */
  start: number;
  end: number;
  /** True when a real duration was reported (otherwise the bar is only a marker). */
  hasDuration: boolean;
  logs: LogEntry[];
  maxLevel: number;
  /** Interesting fields from the first structured log (method, path, status...). */
  details: [string, string][];
}

const DURATION_KEYS = ["durationMs", "duration_ms", "elapsedMs", "latencyMs", "tookMs"];
const DETAIL_KEYS = ["method", "path", "status", "event", "durationMs", "error"];

function payloadOf(log: LogEntry): Record<string, unknown> | null {
  const raw = log.rawMessage ?? "";
  const brace = raw.indexOf("{");
  if (brace < 0) return null;
  try {
    const parsed = JSON.parse(raw.slice(brace));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export function durationOf(log: LogEntry): number | null {
  const payload = payloadOf(log);
  if (payload) {
    for (const key of DURATION_KEYS) {
      const v = payload[key];
      if (typeof v === "number" && Number.isFinite(v) && v >= 0) return v;
    }
  }
  const m = (log.rawMessage ?? "").match(/\b(\d+(?:\.\d+)?)\s?ms\b/i);
  return m ? parseFloat(m[1]) : null;
}

function labelOf(log: LogEntry): string {
  const payload = payloadOf(log);
  if (payload) {
    if (typeof payload.method === "string" && typeof payload.path === "string") return `${payload.method} ${payload.path}`;
    if (typeof payload.event === "string") return payload.event;
  }
  const raw = (log.rawMessage ?? "").trim();
  const brace = raw.indexOf("{");
  const text = (brace > 0 ? raw.slice(0, brace) : raw).trim() || raw;
  return text.length > 48 ? `${text.slice(0, 47)}…` : text;
}

function detailsOf(log: LogEntry): [string, string][] {
  const payload = payloadOf(log);
  if (!payload) return [];
  return DETAIL_KEYS.filter((k) => payload[k] !== undefined && payload[k] !== null).map(
    (k) => [k, String(payload[k])] as [string, string]
  );
}

export function buildSpans(logs: LogEntry[]): TraceSpan[] {
  const groups = new Map<string, LogEntry[]>();
  for (const log of logs) {
    const key = log.spanId ? `${log.serviceName}|${log.spanId}` : `log|${log.logId}`;
    const list = groups.get(key);
    if (list) list.push(log);
    else groups.set(key, [log]);
  }

  const spans: TraceSpan[] = [];
  for (const [key, group] of groups) {
    group.sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
    const times = group.map((l) => Date.parse(l.timestamp));
    let start = times[0];
    let end = times[times.length - 1];

    // The log that reports the longest duration defines how far back the span reaches.
    let hasDuration = false;
    group.forEach((log, i) => {
      const d = durationOf(log);
      if (d !== null && d > 0) {
        hasDuration = true;
        start = Math.min(start, times[i] - d);
        end = Math.max(end, times[i]);
      }
    });

    const first = group.find((l) => payloadOf(l)) ?? group[0];
    spans.push({
      key,
      service: group[0].serviceName,
      spanId: group[0].spanId,
      label: labelOf(first),
      start,
      end,
      hasDuration,
      logs: group,
      maxLevel: Math.max(...group.map((l) => l.logLevel)),
      details: detailsOf(first),
    });
  }
  return spans.sort((a, b) => a.start - b.start || a.end - b.end);
}

export interface TraceScale {
  t0: number;
  total: number;
  log: boolean;
  /** Maps a time (epoch ms) to 0..1 along the axis. */
  position: (t: number) => number;
  ticks: { at: number; label: string }[];
}

export function formatDuration(ms: number): string {
  if (ms < 1) return "<1 ms";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  return `${Math.floor(ms / 60_000)} m ${Math.round((ms % 60_000) / 1000)} s`;
}

/**
 * True when one linear axis would hide the short spans (or a long idle gap would squash
 * everything else): the whole trace is much longer than the typical span.
 */
export function shouldUseLogScale(spans: TraceSpan[]): boolean {
  if (spans.length < 2) return false;
  const t0 = Math.min(...spans.map((s) => s.start));
  const total = Math.max(...spans.map((s) => s.end)) - t0;
  const durations = spans.map((s) => s.end - s.start).sort((a, b) => a - b);
  const median = Math.max(durations[Math.floor(durations.length / 2)], 1);
  return total / median > 40;
}

export function makeScale(spans: TraceSpan[], log: boolean): TraceScale {
  const t0 = Math.min(...spans.map((s) => s.start));
  const total = Math.max(Math.max(...spans.map((s) => s.end)) - t0, 1);

  if (!log) {
    return {
      t0,
      total,
      log,
      position: (t) => (t - t0) / total,
      ticks: [0, 0.25, 0.5, 0.75, 1].map((f) => ({ at: f, label: f === 0 ? "0" : formatDuration(total * f) })),
    };
  }

  // Log axis: compresses large values while keeping order and overlaps (same function for
  // every start and end), with labelled ticks at 0 and powers of ten up to the end.
  const denominator = Math.log1p(total);
  const ticks: { at: number; label: string }[] = [{ at: 0, label: "0" }];
  for (let ms = 1; ms < total; ms *= 10) {
    ticks.push({ at: Math.log1p(ms) / denominator, label: formatDuration(ms) });
  }
  ticks.push({ at: 1, label: formatDuration(total) });
  return {
    t0,
    total,
    log,
    position: (t) => Math.log1p(Math.max(0, t - t0)) / denominator,
    ticks,
  };
}
