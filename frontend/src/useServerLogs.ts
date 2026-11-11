import { useCallback, useEffect, useRef, useState } from "react";
import { searchLogPage, type LogEntry } from "./api";

/** Logs fetched per request. A page on screen is smaller, so one request covers several pages. */
export const CHUNK_SIZE = 100;

export interface ServerQuery {
  words: string[];
  service?: string;
  /** Bump to force a fresh load of an otherwise identical query. */
  nonce?: number;
}

interface Data {
  logs: LogEntry[];
  hasMore: boolean;
  loaded: boolean;
}

const EMPTY: Data = { logs: [], hasMore: false, loaded: false };
const DAY_MS = 24 * 60 * 60 * 1000;

function isOlder(a: LogEntry, b: LogEntry): boolean {
  const ta = Date.parse(a.timestamp);
  const tb = Date.parse(b.timestamp);
  return ta < tb || (ta === tb && a.logId < b.logId);
}

/**
 * Loads logs for a query newest-first in chunks and keeps every chunk already fetched in memory,
 * so moving between pages (forward or back) is free. loadMore() continues after the last loaded
 * log using a cursor; refresh() re-fetches only the newest chunk and merges it in front of what
 * is already cached, so live updates don't throw the older pages away.
 */
export function useServerLogs(query: ServerQuery | null) {
  const [data, setData] = useState<Data>(EMPTY);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const key = query ? JSON.stringify(query) : "";
  const queryRef = useRef(query);
  queryRef.current = query;
  const dataRef = useRef(data);
  dataRef.current = data;
  const genRef = useRef(0);
  const busyRef = useRef(false);
  const fromRef = useRef("");

  const run = useCallback(async (mode: "first" | "more" | "refresh") => {
    const q = queryRef.current;
    if (!q) return;
    if (mode !== "refresh" && busyRef.current) return;

    const gen = genRef.current;
    const now = Date.now();
    if (mode === "first") fromRef.current = new Date(now - DAY_MS).toISOString();
    const last = mode === "more" ? dataRef.current.logs[dataRef.current.logs.length - 1] : undefined;
    if (mode === "more" && !last) return;

    if (mode !== "refresh") {
      busyRef.current = true;
      setLoading(true);
    }
    try {
      const page = await searchLogPage({
        words: q.words,
        service: q.service,
        from: mode === "refresh" ? new Date(now - DAY_MS).toISOString() : fromRef.current,
        to: new Date(now).toISOString(),
        before: last?.timestamp,
        beforeId: last?.logId,
        limit: CHUNK_SIZE,
      });
      if (gen !== genRef.current) return;

      setError(null);
      if (mode === "first") {
        setData({ logs: page.logs, hasMore: page.hasMore, loaded: true });
      } else if (mode === "more") {
        setData((prev) => ({ logs: [...prev.logs, ...page.logs], hasMore: page.hasMore, loaded: true }));
      } else {
        setData((prev) => {
          if (page.logs.length === 0) return prev;
          const oldest = page.logs[page.logs.length - 1];
          const overlaps = prev.logs.some((l) => l.logId === oldest.logId);
          if (!page.hasMore) return { logs: page.logs, hasMore: false, loaded: true };
          if (!overlaps) return { logs: page.logs, hasMore: true, loaded: true };
          return {
            logs: [...page.logs, ...prev.logs.filter((l) => isOlder(l, oldest))],
            hasMore: prev.hasMore,
            loaded: true,
          };
        });
      }
    } catch (e) {
      if (gen === genRef.current && mode !== "refresh") setError((e as Error).message);
    } finally {
      if (gen === genRef.current && mode !== "refresh") {
        busyRef.current = false;
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    genRef.current += 1;
    busyRef.current = false;
    setData(EMPTY);
    setError(null);
    setLoading(false);
    if (key) void run("first");
  }, [key, run]);

  const loadMore = useCallback(() => void run("more"), [run]);
  const refresh = useCallback(() => void run("refresh"), [run]);

  return { ...data, loading, error, loadMore, refresh };
}
