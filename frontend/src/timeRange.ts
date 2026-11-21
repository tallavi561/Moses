/**
 * A log search's time window: either "the last N minutes" (moves with the clock, so live views
 * keep refreshing) or a fixed start with an optional end (no end = until now, still live).
 */
export type TimeRange = { minutes: number } | { from: string; to?: string };

/** Logs are kept this long (ingestion's TTL index); older starts can't return anything. */
export const LOG_RETENTION_DAYS = 14;

export const DEFAULT_RANGE: TimeRange = { minutes: 24 * 60 };

export const RANGE_PRESETS = [15, 60, 24 * 60, 7 * 24 * 60, LOG_RETENTION_DAYS * 24 * 60] as const;

/** The concrete [from, to] to ask the server for, right now. */
export function resolveRange(range: TimeRange, now = Date.now()): { from: string; to: string } {
  if ("minutes" in range) {
    return { from: new Date(now - range.minutes * 60_000).toISOString(), to: new Date(now).toISOString() };
  }
  return { from: range.from, to: range.to ?? new Date(now).toISOString() };
}

/** True when the window ends "now", so new logs can still appear in it. */
export function isLive(range: TimeRange): boolean {
  return "minutes" in range || !range.to;
}

/** Reads ?range=<minutes> or ?from=<iso>[&to=<iso>]; anything invalid is ignored. */
export function rangeFromParams(params: URLSearchParams): TimeRange | null {
  const minutes = Number(params.get("range"));
  if (Number.isInteger(minutes) && minutes > 0 && minutes <= LOG_RETENTION_DAYS * 24 * 60) {
    return { minutes };
  }
  const from = params.get("from");
  if (!from || Number.isNaN(Date.parse(from))) return null;
  const to = params.get("to");
  if (to && (Number.isNaN(Date.parse(to)) || Date.parse(to) <= Date.parse(from))) return { from };
  return to ? { from, to } : { from };
}

/** Writes the range into URL params (replacing any previous range params). */
export function rangeToParams(range: TimeRange, params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete("range");
  next.delete("from");
  next.delete("to");
  if ("minutes" in range) {
    if (range.minutes !== (DEFAULT_RANGE as { minutes: number }).minutes) next.set("range", String(range.minutes));
  } else {
    next.set("from", range.from);
    if (range.to) next.set("to", range.to);
  }
  return next;
}

/** What's wrong with a custom range, or null. `from`/`to` are what the user picked. */
export function validateCustom(from: Date | null, to: Date | null, now = Date.now()): "missing" | "order" | "retention" | null {
  if (!from || Number.isNaN(from.getTime())) return "missing";
  if (to && to.getTime() <= from.getTime()) return "order";
  if ((to ? to.getTime() : now) < now - LOG_RETENTION_DAYS * 24 * 3_600_000) return "retention";
  return null;
}

/** "2026-11-21T10:05" in local time, for <input type="datetime-local">. */
export function toLocalInput(iso: string | undefined | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
