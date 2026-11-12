import { useEffect, useMemo, useState } from "react";
import { fetchTrace, type LogEntry } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { LEVEL_CLASS, LEVEL_LABEL } from "../logLevels";
import {
  buildSpans,
  formatDuration,
  makeScale,
  shouldUseLogScale,
  type TraceScale,
  type TraceSpan,
} from "../traceSpans";

interface TraceViewProps {
  traceId: string;
  onClose: () => void;
}

/** Distinct, theme-independent colours so a service keeps its colour on every row. */
const SERVICE_PALETTE = ["#5b9bd5", "#ffc000", "#00b050", "#ed7d31", "#9e6bd1", "#26c6da", "#ef5b8f", "#a5a5a5"];

const ROW_HEIGHT = 34;
const LABEL_WIDTH = 170;

function LevelBadge({ level }: { level: number }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full font-code text-code-sm font-semibold ${
        LEVEL_CLASS[level] ?? LEVEL_CLASS[1]
      }`}
    >
      {LEVEL_LABEL[level] ?? String(level)}
    </span>
  );
}

interface HoverState {
  span: TraceSpan;
  x: number;
  y: number;
}

function SpanTooltip({ hover, scale }: { hover: HoverState; scale: TraceScale }) {
  const { t, lang } = useLanguage();
  const { span } = hover;
  const duration = span.end - span.start;
  const width = 340;
  const left = Math.min(hover.x + 14, window.innerWidth - width - 12);
  const top = Math.min(hover.y + 14, window.innerHeight - 260);
  const locale = lang === "he" ? "he-IL" : "en-US";

  return (
    <div
      role="tooltip"
      className="fixed z-50 pointer-events-none bg-surface-container-highest text-on-surface rounded-xl shadow-xl p-space-sm font-body-sm text-body-sm"
      style={{ left: Math.max(8, left), top: Math.max(8, top), width }}
    >
      <div className="flex items-center gap-space-xs mb-1">
        <span className="font-headline text-headline-sm truncate">{span.service}</span>
        <LevelBadge level={span.maxLevel} />
      </div>
      <p className="font-code text-code-sm text-secondary mb-space-xs break-words">{span.label}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-space-sm gap-y-0.5 font-code text-code-sm">
        <dt className="text-on-surface-variant">{t.search.traceDuration}</dt>
        <dd>{span.hasDuration ? formatDuration(duration) : t.search.traceNoDuration}</dd>
        <dt className="text-on-surface-variant">{t.search.traceStart}</dt>
        <dd>
          +{formatDuration(span.start - scale.t0)}
          <span className="text-on-surface-variant"> ({new Date(span.start).toLocaleTimeString(locale)})</span>
        </dd>
        {span.hasDuration && (
          <>
            <dt className="text-on-surface-variant">{t.search.traceEnd}</dt>
            <dd>+{formatDuration(span.end - scale.t0)}</dd>
          </>
        )}
        {span.spanId && (
          <>
            <dt className="text-on-surface-variant">{t.search.traceSpanId}</dt>
            <dd className="break-all">{span.spanId}</dd>
          </>
        )}
        {span.details
          .filter(([k]) => k !== "method" && k !== "path" && k !== "durationMs")
          .map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="text-on-surface-variant">{k}</dt>
              <dd className="break-all">{v}</dd>
            </div>
          ))}
      </dl>
      <div className="mt-space-xs border-t border-outline-variant/30 pt-space-xs">
        <span className="text-on-surface-variant font-label-sm text-label-sm uppercase tracking-wider">
          {t.search.traceLogsInSpan} ({span.logs.length})
        </span>
        {span.logs.slice(0, 3).map((log) => (
          <p key={log.logId} className="font-code text-code-sm text-on-surface break-words line-clamp-2">
            {log.rawMessage}
          </p>
        ))}
        {span.logs.length > 3 && <p className="font-code text-code-sm text-on-surface-variant">+{span.logs.length - 3}</p>}
      </div>
    </div>
  );
}

/** The tooltip's content in full (every log, untruncated), shown inline when a row is clicked. */
function SpanDetailsPanel({ span, scale, onCollapse }: { span: TraceSpan; scale: TraceScale; onCollapse: () => void }) {
  const { t, lang } = useLanguage();
  const duration = span.end - span.start;
  const locale = lang === "he" ? "he-IL" : "en-US";
  return (
    <div className="bg-surface-container-high rounded-xl p-space-sm mb-space-xs font-body-sm text-body-sm text-on-surface">
      <div className="flex items-start justify-between gap-space-sm mb-1">
        <div className="flex items-center gap-space-xs min-w-0">
          <span className="font-headline text-headline-sm truncate">{span.service}</span>
          <LevelBadge level={span.maxLevel} />
        </div>
        <button
          type="button"
          className="shrink-0 flex items-center gap-1 px-space-sm py-0.5 rounded-lg bg-surface-container hover:bg-surface-bright font-label-md text-label-md transition-colors"
          onClick={onCollapse}
        >
          <Icon name="close" size={14} />
          {t.search.traceCollapse}
        </button>
      </div>
      <p className="font-code text-code-sm text-secondary mb-space-xs break-words">{span.label}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-space-sm gap-y-0.5 font-code text-code-sm">
        <dt className="text-on-surface-variant">{t.search.traceDuration}</dt>
        <dd>{span.hasDuration ? formatDuration(duration) : t.search.traceNoDuration}</dd>
        <dt className="text-on-surface-variant">{t.search.traceStart}</dt>
        <dd>
          +{formatDuration(span.start - scale.t0)}
          <span className="text-on-surface-variant"> ({new Date(span.start).toLocaleTimeString(locale)})</span>
        </dd>
        {span.hasDuration && (
          <>
            <dt className="text-on-surface-variant">{t.search.traceEnd}</dt>
            <dd>+{formatDuration(span.end - scale.t0)}</dd>
          </>
        )}
        {span.spanId && (
          <>
            <dt className="text-on-surface-variant">{t.search.traceSpanId}</dt>
            <dd className="break-all">{span.spanId}</dd>
          </>
        )}
        {span.details
          .filter(([k]) => k !== "method" && k !== "path" && k !== "durationMs")
          .map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="text-on-surface-variant">{k}</dt>
              <dd className="break-all">{v}</dd>
            </div>
          ))}
      </dl>
      <div className="mt-space-xs border-t border-outline-variant/30 pt-space-xs">
        <span className="text-on-surface-variant font-label-sm text-label-sm uppercase tracking-wider">
          {t.search.traceLogsInSpan} ({span.logs.length})
        </span>
        {span.logs.map((log) => (
          <p key={log.logId} className="font-code text-code-sm text-on-surface whitespace-pre-wrap break-words mt-1">
            {log.rawMessage}
          </p>
        ))}
      </div>
    </div>
  );
}

function Timeline({ spans, logScale, scale }: { spans: TraceSpan[]; logScale: boolean; scale: TraceScale }) {
  const { t } = useLanguage();
  const [hover, setHover] = useState<HoverState | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const toggle = (key: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  const services = useMemo(() => [...new Set(spans.map((s) => s.service))], [spans]);
  const colorOf = (service: string) => SERVICE_PALETTE[services.indexOf(service) % SERVICE_PALETTE.length];

  return (
    // The chart is always left-to-right (time flows rightwards) even in the Hebrew UI.
    <div dir="ltr" className="overflow-x-auto">
      <div style={{ minWidth: 460 }}>
        <div className="flex">
          <div style={{ width: LABEL_WIDTH }} className="shrink-0" />
          <div className="relative flex-1 h-6 border-b border-outline-variant/40">
            {scale.ticks.map((tick, i) => (
              <span
                key={`${tick.label}-${i}`}
                className="absolute top-0 font-code text-code-sm text-on-surface-variant whitespace-nowrap"
                style={{
                  left: `${tick.at * 100}%`,
                  transform: tick.at >= 0.97 ? "translateX(-100%)" : tick.at <= 0.03 ? "none" : "translateX(-50%)",
                }}
              >
                {tick.label}
              </span>
            ))}
          </div>
        </div>

        <div className="flex flex-col">
          {spans.map((span) => {
            const x0 = scale.position(span.start);
            const x1 = scale.position(span.end);
            const color = colorOf(span.service);
            const isError = span.maxLevel >= 4;
            const duration = span.end - span.start;
            const isOpen = expanded.has(span.key);
            const hovered = hover?.span.key === span.key;
            const labelOnRight = x1 <= 0.8;
            return (
              <div key={span.key}>
              <div
                role="button"
                tabIndex={0}
                aria-expanded={isOpen}
                className={`flex items-center cursor-pointer transition-colors ${hovered || isOpen ? "bg-surface-container-high" : ""}`}
                style={{ height: ROW_HEIGHT }}
                onClick={() => {
                  toggle(span.key);
                  setHover(null);
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    toggle(span.key);
                  }
                }}
                onMouseMove={(e) => setHover({ span, x: e.clientX, y: e.clientY })}
                onMouseLeave={() => setHover(null)}
              >
                <div style={{ width: LABEL_WIDTH }} className="shrink-0 pr-space-sm min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full shrink-0" style={{ background: color }} />
                    <span className="font-body-sm text-body-sm text-on-surface truncate">{span.service}</span>
                  </div>
                  <div className="font-code text-code-sm text-on-surface-variant truncate pl-3.5">{span.label}</div>
                </div>
                <div className="relative flex-1 h-full">
                  {/* faint guide lines at the axis ticks */}
                  {scale.ticks.map((tick, i) => (
                    <span
                      key={i}
                      className="absolute top-0 bottom-0 w-px bg-outline-variant/20"
                      style={{ left: `min(${tick.at * 100}%, calc(100% - 1px))` }}
                    />
                  ))}
                  <span
                    className={`absolute top-1/2 -translate-y-1/2 h-5 rounded-md transition-transform ${
                      hovered ? "scale-y-110 shadow-lg" : ""
                    } ${isError ? "ring-2 ring-error" : ""}`}
                    style={{
                      left: `min(${x0 * 100}%, calc(100% - 6px))`, // markers at the very end must stay inside
                      width: `${Math.max((x1 - x0) * 100, 0)}%`,
                      minWidth: 6,
                      background: color,
                      opacity: span.hasDuration ? 1 : 0.75,
                    }}
                  />
                  <span
                    className="absolute top-1/2 -translate-y-1/2 font-code text-code-sm text-on-surface whitespace-nowrap pointer-events-none"
                    style={
                      labelOnRight
                        ? { left: `calc(${x1 * 100}% + 8px)` }
                        : { right: `calc(${(1 - x0) * 100}% + 8px)` }
                    }
                  >
                    {span.hasDuration ? formatDuration(duration) : "•"}
                  </span>
                </div>
              </div>
              {isOpen && <SpanDetailsPanel span={span} scale={scale} onCollapse={() => toggle(span.key)} />}
              </div>
            );
          })}
        </div>

        {logScale && (
          <p className="flex items-center gap-1 font-body-sm text-body-sm text-on-surface-variant mt-space-sm">
            <Icon name="info" size={15} />
            {t.search.traceLogNote}
          </p>
        )}
      </div>
      {hover && !expanded.has(hover.span.key) && <SpanTooltip hover={hover} scale={scale} />}
    </div>
  );
}

function LogsList({ logs }: { logs: LogEntry[] }) {
  const t0 = Date.parse(logs[0].timestamp);
  const services = [...new Set(logs.map((l) => l.serviceName))];
  return (
    <div className="flex flex-col gap-space-xs">
      {logs.map((log) => (
        <div
          key={log.logId}
          className="bg-surface-container rounded-xl px-space-md py-space-sm transition-all duration-200 hover:bg-surface-container-high hover:shadow-md"
        >
          <div className="flex items-center gap-space-sm flex-wrap">
            <span className="font-code text-code-sm text-secondary w-20 shrink-0">
              +{formatDuration(Date.parse(log.timestamp) - t0)}
            </span>
            <span
              className="w-2.5 h-2.5 rounded-full shrink-0"
              style={{ background: SERVICE_PALETTE[services.indexOf(log.serviceName) % SERVICE_PALETTE.length] }}
            />
            <span className="font-headline text-headline-sm text-on-surface">{log.serviceName}</span>
            <LevelBadge level={log.logLevel} />
            {log.spanId && <span className="font-code text-code-sm text-on-surface-variant">span {log.spanId}</span>}
          </div>
          <p className="font-code text-code-sm text-on-surface whitespace-pre-wrap break-words mt-space-xs">{log.rawMessage}</p>
        </div>
      ))}
    </div>
  );
}

/**
 * One request across services: a Gantt-style timeline (one bar per span, length proportional to
 * its duration, log-compressed when the spread is too large) plus the plain list of its logs.
 */
export default function TraceView({ traceId, onClose }: TraceViewProps) {
  const { t } = useLanguage();
  const [logs, setLogs] = useState<LogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<"timeline" | "logs">("timeline");
  const [logScale, setLogScale] = useState<boolean | null>(null); // null = decide automatically

  useEffect(() => {
    let cancelled = false;
    setLogs(null);
    setError(null);
    setLogScale(null);
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

  const spans = useMemo(() => (logs && logs.length > 0 ? buildSpans(logs) : []), [logs]);
  const useLog = logScale ?? shouldUseLogScale(spans);
  const scale = useMemo(() => (spans.length > 0 ? makeScale(spans, useLog) : null), [spans, useLog]);

  const summary = useMemo(() => {
    if (!logs || logs.length === 0 || !scale) return null;
    return {
      duration: scale.total,
      services: [...new Set(logs.map((l) => l.serviceName))],
      errors: logs.filter((l) => l.logLevel >= 4).length,
    };
  }, [logs, scale]);

  const tabClass = (active: boolean) =>
    `px-space-sm py-0.5 rounded-md font-code text-code-sm transition-all ${
      active ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
    }`;

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
      ) : !summary || !scale ? (
        <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
          <Icon name="search_off" size={28} />
          <span className="font-body-md text-body-md">{t.search.traceEmpty}</span>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-between gap-space-sm mb-space-md">
            <div className="flex flex-wrap items-center gap-space-xs">
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
            </div>

            <div className="flex items-center gap-space-sm">
              {tab === "timeline" && (
                <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup">
                  <button type="button" className={tabClass(!useLog)} aria-pressed={!useLog} onClick={() => setLogScale(false)}>
                    {t.search.traceScaleLinear}
                  </button>
                  <button type="button" className={tabClass(useLog)} aria-pressed={useLog} onClick={() => setLogScale(true)}>
                    {t.search.traceScaleLog}
                  </button>
                </div>
              )}
              <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup">
                <button type="button" className={tabClass(tab === "timeline")} aria-pressed={tab === "timeline"} onClick={() => setTab("timeline")}>
                  {t.search.traceTabTimeline}
                </button>
                <button type="button" className={tabClass(tab === "logs")} aria-pressed={tab === "logs"} onClick={() => setTab("logs")}>
                  {t.search.traceTabLogs}
                </button>
              </div>
            </div>
          </div>

          {tab === "timeline" ? <Timeline spans={spans} logScale={useLog} scale={scale} /> : <LogsList logs={logs} />}
        </>
      )}
    </div>
  );
}
