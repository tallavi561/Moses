import { useEffect, useMemo, useState } from "react";
import { fetchTrace, type LogEntry } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { LEVEL_CLASS, LEVEL_LABEL } from "../logLevels";

interface TraceViewProps {
  traceId: string;
  onClose: () => void;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  return `${Math.floor(ms / 60_000)} m ${Math.round((ms % 60_000) / 1000)} s`;
}

/** Stable accent per service so the same service keeps its colour down the whole timeline. */
const SERVICE_COLORS = [
  "bg-secondary",
  "bg-tertiary",
  "bg-primary-container",
  "bg-inverse-primary",
  "bg-outline",
  "bg-secondary-container",
];

/**
 * Everything that happened for one request, oldest first: each log is a row positioned on a
 * time track proportionally to its offset from the first log of the trace, so it reads as a
 * waterfall of which service did what, when.
 */
export default function TraceView({ traceId, onClose }: TraceViewProps) {
  const { t } = useLanguage();
  const [logs, setLogs] = useState<LogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLogs(null);
    setError(null);
    fetchTrace(traceId)
      .then((data) => {
        if (!cancelled) setLogs(data);
      })
      .catch((e) => {
        if (!cancelled) setError((e as Error).message);
      });
    return () => {
      cancelled = true;
    };
  }, [traceId]);

  const summary = useMemo(() => {
    if (!logs || logs.length === 0) return null;
    const times = logs.map((l) => Date.parse(l.timestamp));
    const start = Math.min(...times);
    const end = Math.max(...times);
    const services = [...new Set(logs.map((l) => l.serviceName))];
    const errors = logs.filter((l) => l.logLevel >= 4).length;
    return { start, span: Math.max(end - start, 1), duration: end - start, services, errors };
  }, [logs]);

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
      <div className="flex items-start justify-between gap-space-sm mb-space-sm">
        <div className="min-w-0">
          <h2 className="font-headline text-headline-md text-on-surface">{t.search.traceTitle}</h2>
          <p className="font-code text-code-sm text-on-surface-variant break-all">{traceId}</p>
        </div>
        <button
          type="button"
          className="shrink-0 flex items-center gap-1 px-space-sm py-1 rounded-lg bg-surface-container-high hover:bg-surface-bright text-on-surface font-label-md text-label-md transition-colors"
          onClick={onClose}
        >
          <Icon name="close" size={16} />
          {t.search.traceClose}
        </button>
      </div>

      {error ? (
        <div className="flex items-center gap-space-sm font-body-md text-body-md text-error">
          <Icon name="error" size={18} />
          {t.search.errorPrefix}: {error}
        </div>
      ) : logs === null ? (
        <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
          <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
          {t.search.loading}
        </div>
      ) : !summary ? (
        <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
          <Icon name="search_off" size={28} />
          <span className="font-body-md text-body-md">{t.search.traceEmpty}</span>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-space-xs mb-space-md">
            <span className="font-code text-code-sm text-on-surface-variant bg-surface-container px-space-sm py-0.5 rounded-full">
              {t.search.traceSummary
                .replace("{count}", String(logs.length))
                .replace("{services}", String(summary.services.length))
                .replace("{duration}", formatDuration(summary.duration))}
            </span>
            {summary.errors > 0 && (
              <span className="font-code text-code-sm bg-primary-container/20 text-primary px-space-sm py-0.5 rounded-full">
                {t.search.traceErrors.replace("{count}", String(summary.errors))}
              </span>
            )}
            {summary.services.map((s) => (
              <span
                key={s}
                className="flex items-center gap-1 font-code text-code-sm text-on-surface bg-surface-container px-space-sm py-0.5 rounded-full"
              >
                <span className={`w-2 h-2 rounded-full ${SERVICE_COLORS[summary.services.indexOf(s) % SERVICE_COLORS.length]}`} />
                {s}
              </span>
            ))}
          </div>

          <div className="flex flex-col gap-space-xs">
            {logs.map((log) => {
              const offset = Date.parse(log.timestamp) - summary.start;
              const pct = (offset / summary.span) * 100;
              const color = SERVICE_COLORS[summary.services.indexOf(log.serviceName) % SERVICE_COLORS.length];
              return (
                <div
                  key={log.logId}
                  className="bg-surface-container rounded-xl px-space-md py-space-sm transition-all duration-200 hover:bg-surface-container-high hover:shadow-md"
                >
                  <div className="flex items-center gap-space-sm flex-wrap">
                    <span className="font-code text-code-sm text-secondary w-20 shrink-0">+{formatDuration(offset)}</span>
                    <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${color}`} />
                    <span className="font-headline text-headline-sm text-on-surface">{log.serviceName}</span>
                    <span
                      className={`inline-flex items-center px-2 py-0.5 rounded-full font-code text-code-sm font-semibold ${
                        LEVEL_CLASS[log.logLevel] ?? LEVEL_CLASS[1]
                      }`}
                    >
                      {LEVEL_LABEL[log.logLevel] ?? String(log.logLevel)}
                    </span>
                    {log.spanId && (
                      <span className="font-code text-code-sm text-on-surface-variant">span {log.spanId}</span>
                    )}
                  </div>
                  <div className="relative h-1 rounded-full bg-surface-container-highest mt-space-xs mb-space-xs">
                    <span
                      className={`absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-2 h-2 rounded-full ${color}`}
                      style={{ left: `${pct}%` }}
                    />
                  </div>
                  <p className="font-code text-code-sm text-on-surface whitespace-pre-wrap break-words">{log.rawMessage}</p>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
