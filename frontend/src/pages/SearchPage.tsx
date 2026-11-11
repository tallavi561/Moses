import { useEffect, useState } from "react";
import { askAi, fetchProcessState, type LogEntry } from "../api";
import { useLanguage, pluralize } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { useServerLogs, type ServerQuery } from "../useServerLogs";
import { LEVEL_CLASS, LEVEL_LABEL } from "../logLevels";
import TraceView from "../components/TraceView";
import InfoHint from "../components/InfoHint";

const PAGE_SIZE = 20;
const FEED_REFRESH_MS = 10000;

function TraceChip({ traceId, onTrace }: { traceId: string; onTrace: (traceId: string) => void }) {
  const { t } = useLanguage();
  return (
    <button
      type="button"
      title={traceId}
      className="flex items-center gap-1 w-fit px-space-sm py-0.5 rounded-full bg-secondary-container/20 text-secondary font-code text-code-sm hover:bg-secondary-container/40 transition-colors"
      onClick={() => onTrace(traceId)}
    >
      <Icon name="account_tree" size={13} />
      {t.search.traceOpen}
    </button>
  );
}

function LevelBadge({ level }: { level: number }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full font-code text-code-sm font-semibold ${
        LEVEL_CLASS[level] ?? "bg-surface-container-high text-on-surface-variant"
      }`}
    >
      {LEVEL_LABEL[level] ?? String(level)}
    </span>
  );
}

function parseLogToJson(log: LogEntry): Record<string, unknown> {
  const base: Record<string, unknown> = {
    ts: log.timestamp,
    service: log.serviceName,
    level: LEVEL_LABEL[log.logLevel] ?? log.logLevel,
  };
  if (log.traceId) base.traceId = log.traceId;
  if (log.spanId) base.spanId = log.spanId;

  const raw = log.rawMessage ?? "";
  const brace = raw.indexOf("{");
  if (brace >= 0) {
    try {
      const payload = JSON.parse(raw.slice(brace));
      if (payload && typeof payload === "object" && !Array.isArray(payload)) {
        const prefix = raw.slice(0, brace).trim();
        if (prefix) base.event = prefix;
        for (const [key, value] of Object.entries(payload as Record<string, unknown>)) {
          if (key !== "ts" && key !== "service") base[key] = value;
        }
        return base;
      }
    } catch {
      // not JSON - fall through to the plain message
    }
  }
  base.message = raw;
  return base;
}

function LogJsonList({ logs, onTrace }: { logs: LogEntry[]; onTrace: (traceId: string) => void }) {
  return (
    <div className="flex flex-col gap-space-xs">
      {logs.map((log) => (
        <div
          key={log.logId}
          className="bg-surface-container rounded-xl px-space-md py-space-sm cursor-default transition-all duration-200 ease-out hover:bg-surface-container-high hover:shadow-md hover:translate-x-0.5"
        >
          <pre className="font-code text-code-sm text-on-surface overflow-x-auto whitespace-pre-wrap break-words">
            {JSON.stringify(parseLogToJson(log), null, 2)}
          </pre>
          {log.traceId && (
            <div className="mt-space-xs">
              <TraceChip traceId={log.traceId} onTrace={onTrace} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function LogTable({ logs, onTrace }: { logs: LogEntry[]; onTrace: (traceId: string) => void }) {
  const { t, lang } = useLanguage();
  return (
    <div className="overflow-x-auto rounded-xl">
      <table className="w-full border-collapse">
        <thead>
          <tr className="text-left">
            {[t.search.columns.time, t.search.columns.service, t.search.columns.level, t.search.columns.ip, t.search.columns.message].map(
              (label) => (
                <th key={label} className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                  {label}
                </th>
              )
            )}
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.logId} className="odd:bg-surface-container/40 hover:bg-surface-container-high transition-colors">
              <td className="font-code text-code-sm text-on-surface-variant px-space-sm py-space-xs whitespace-nowrap">
                {new Date(log.timestamp).toLocaleString(lang === "he" ? "he-IL" : "en-US")}
              </td>
              <td className="font-body-sm text-body-sm text-on-surface px-space-sm py-space-xs whitespace-nowrap">
                {log.serviceName}
              </td>
              <td className="px-space-sm py-space-xs">
                <LevelBadge level={log.logLevel} />
              </td>
              <td className="font-code text-code-sm text-on-surface-variant px-space-sm py-space-xs whitespace-nowrap">
                {log.ip}
              </td>
              <td className="font-code text-code-sm text-on-surface px-space-sm py-space-xs">
                {log.rawMessage}
                {log.traceId && (
                  <div className="mt-1">
                    <TraceChip traceId={log.traceId} onTrace={onTrace} />
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Page numbers to show: first, last, and a window around the current page, with gaps as null. */
function pageWindow(current: number, total: number): (number | null)[] {
  const wanted = new Set<number>([0, total - 1]);
  for (let i = current - 2; i <= current + 2; i++) {
    if (i >= 0 && i < total) wanted.add(i);
  }
  const sorted = [...wanted].sort((a, b) => a - b);
  const result: (number | null)[] = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1] > 1) result.push(null);
    result.push(p);
  });
  return result;
}

interface PagedLogListProps {
  logs: LogEntry[];
  hasMore: boolean;
  loading: boolean;
  loadMore: () => void;
  /** Page goes back to 1 whenever this changes (a new query). */
  resetKey: string;
  view: "json" | "table";
  onTrace: (traceId: string) => void;
  onRefresh?: () => void;
}

/**
 * Shows PAGE_SIZE logs at a time out of the logs already cached by the caller. Moving between
 * loaded pages makes no request; when the viewed page gets within one page of the end of the
 * cache, the next chunk is fetched in the background so the following page is ready.
 */
function PagedLogList({ logs, hasMore, loading, loadMore, resetKey, view, onTrace, onRefresh }: PagedLogListProps) {
  const [page, setPage] = useState(0);

  useEffect(() => setPage(0), [resetKey]);

  const loadedPages = Math.max(1, Math.ceil(logs.length / PAGE_SIZE));
  const current = Math.min(page, loadedPages - 1);

  useEffect(() => {
    if (hasMore && !loading && (current + 2) * PAGE_SIZE > logs.length) loadMore();
  }, [current, hasMore, loading, logs.length, loadMore]);

  useEffect(() => {
    if (!onRefresh || current !== 0) return;
    const interval = setInterval(onRefresh, FEED_REFRESH_MS);
    return () => clearInterval(interval);
  }, [onRefresh, current]);

  const pageLogs = logs.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE);
  const atLastLoaded = current >= loadedPages - 1;
  const showPager = loadedPages > 1 || hasMore;

  const pageButton =
    "min-w-8 h-8 px-2 rounded-lg font-code text-code-sm transition-colors flex items-center justify-center";

  return (
    <>
      {view === "json" ? (
        <LogJsonList logs={pageLogs} onTrace={onTrace} />
      ) : (
        <LogTable logs={pageLogs} onTrace={onTrace} />
      )}

      {showPager && (
        <div className="flex items-center justify-center gap-1 mt-space-md flex-wrap">
          <button
            type="button"
            className={`${pageButton} text-on-surface-variant hover:bg-surface-container-high disabled:opacity-30 disabled:hover:bg-transparent`}
            onClick={() => setPage(current - 1)}
            disabled={current === 0}
            aria-label="Previous page"
          >
            <Icon name="chevron_left" size={18} />
          </button>

          {pageWindow(current, loadedPages).map((p, i) =>
            p === null ? (
              <span key={`gap-${i}`} className="text-on-surface-variant px-1">
                …
              </span>
            ) : (
              <button
                key={p}
                type="button"
                className={`${pageButton} ${
                  p === current
                    ? "bg-primary-container text-on-primary-container"
                    : "text-on-surface-variant hover:bg-surface-container-high"
                }`}
                onClick={() => setPage(p)}
                aria-current={p === current ? "page" : undefined}
              >
                {p + 1}
              </button>
            )
          )}

          {hasMore && (
            <span className={`${pageButton} text-on-surface-variant`} title="More logs available">
              {loading ? <Icon name="progress_activity" size={16} className="animate-spin text-secondary" /> : "…"}
            </span>
          )}

          <button
            type="button"
            className={`${pageButton} text-on-surface-variant hover:bg-surface-container-high disabled:opacity-30 disabled:hover:bg-transparent`}
            onClick={() => setPage(current + 1)}
            disabled={atLastLoaded}
            aria-label="Next page"
          >
            <Icon name="chevron_right" size={18} />
          </button>
        </div>
      )}
    </>
  );
}

type SearchMode = "structured" | "ai";

const SEARCH_MODE_KEY = "moses.search.mode";

const FEED_QUERY: ServerQuery = { words: [] };

export default function SearchPage() {
  const { t } = useLanguage();
  const [searchMode, setSearchMode] = useState<SearchMode>(() => {
    try {
      return localStorage.getItem(SEARCH_MODE_KEY) === "ai" ? "ai" : "structured";
    } catch {
      return "structured";
    }
  });
  const [wordsInput, setWordsInput] = useState("");
  const [serviceFilter, setServiceFilter] = useState("");
  const [serviceNames, setServiceNames] = useState<string[]>([]);
  const [nlQuestion, setNlQuestion] = useState("");
  const [structuredQuery, setStructuredQuery] = useState<ServerQuery | null>(null);
  const [aiResults, setAiResults] = useState<LogEntry[] | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastMode, setLastMode] = useState<"structured" | "ai" | null>(null);
  const [resultsView, setResultsView] = useState<"json" | "table">("json");
  const [traceId, setTraceId] = useState<string | null>(null);

  const feed = useServerLogs(FEED_QUERY);
  const search = useServerLogs(structuredQuery);

  useEffect(() => {
    fetchProcessState()
      .then((nodes) => setServiceNames(nodes.map((n) => n.serviceName).sort()))
      .catch(() => setServiceNames([]));
  }, []);

  function openTrace(id: string) {
    setTraceId(id);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function runStructuredSearch() {
    // A pasted trace id (UUID / long hex, or explicit "trace:<id>") opens the trace directly:
    // ids are masked by the tokenizer, so they can never match as ordinary search words.
    const input = wordsInput.trim();
    const explicit = input.match(/^trace:(\S+)$/i);
    const looksLikeTraceId = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(input) || /^[0-9a-f-]{16,}$/i.test(input);
    if (explicit || looksLikeTraceId) {
      setError(null);
      openTrace(explicit ? explicit[1] : input);
      return;
    }
    const words = wordsInput.split(/\s+/).filter(Boolean);
    if (words.length === 0 && !serviceFilter) {
      setError(t.search.needsWord);
      return;
    }
    setError(null);
    setLastMode("structured");
    setAiResults(null);
    setStructuredQuery({ words, service: serviceFilter || undefined, nonce: Date.now() });
  }

  async function runNlQuery() {
    if (!nlQuestion.trim()) {
      setError(t.search.needsQuestion);
      return;
    }
    setAiLoading(true);
    setError(null);
    setLastMode("ai");
    setStructuredQuery(null);
    try {
      const res = await askAi(nlQuestion);
      const typed = res as { results?: LogEntry[] };
      setAiResults([...(typed.results ?? [])].reverse());
    } catch (e) {
      setError(`${t.search.aiErrorPrefix}: ${(e as Error).message}`);
      setAiResults(null);
    } finally {
      setAiLoading(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent, action: () => void) {
    if (e.key === "Enter") action();
  }

  function changeSearchMode(next: SearchMode) {
    setSearchMode(next);
    try {
      localStorage.setItem(SEARCH_MODE_KEY, next);
    } catch {
      // ignore
    }
  }

  const showFeed = lastMode === null;
  const showAi = lastMode === "ai";
  const searchError = lastMode === "structured" && search.error ? `${t.search.errorPrefix}: ${search.error}` : null;
  const visibleError = error ?? searchError;

  const resultsLoading = showAi ? aiLoading : !search.loaded && search.loading;
  const resultCount = showAi ? aiResults?.length ?? 0 : search.logs.length;

  const viewToggle = (
    <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup">
      {(["json", "table"] as const).map((view) => (
        <button
          key={view}
          type="button"
          className={`px-space-sm py-0.5 rounded-md font-code text-code-sm transition-all ${
            resultsView === view ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
          }`}
          onClick={() => setResultsView(view)}
          aria-pressed={resultsView === view}
        >
          {view === "json" ? t.search.viewJson : t.search.viewTable}
        </button>
      ))}
    </div>
  );

  const spinner = (
    <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
      <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
      {t.search.loading}
    </div>
  );

  const empty = (message: string) => (
    <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
      <Icon name="search_off" size={28} />
      <span className="font-body-md text-body-md">{message}</span>
    </div>
  );

  return (
    <>
      <div className="flex items-center bg-surface-container-high rounded-xl p-0.5 shadow-inner w-fit" role="radiogroup">
        <button
          type="button"
          className={`flex items-center gap-1.5 px-space-md py-1.5 rounded-lg font-headline text-headline-sm transition-all ${
            searchMode === "structured" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
          }`}
          onClick={() => changeSearchMode("structured")}
          aria-pressed={searchMode === "structured"}
        >
          <Icon name="rule" size={16} />
          {t.search.structuredTitle}
        </button>
        <button
          type="button"
          className={`flex items-center gap-1.5 px-space-md py-1.5 rounded-lg font-headline text-headline-sm transition-all ${
            searchMode === "ai" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
          }`}
          onClick={() => changeSearchMode("ai")}
          aria-pressed={searchMode === "ai"}
        >
          <Icon name="auto_awesome" size={16} />
          {t.search.nlTitle}
        </button>
      </div>

      <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
        {searchMode === "structured" ? (
          <>
            <div className="flex items-center gap-space-xs">
              <h2 className="font-headline text-headline-md text-on-surface">{t.search.structuredTitle}</h2>
              <InfoHint title={t.search.helpTitle}>
                <ul className="flex flex-col gap-space-xs list-disc ps-space-md text-on-surface-variant">
                  <li>{t.search.helpWords}</li>
                  <li>{t.search.helpService}</li>
                  <li>{t.search.helpTrace}</li>
                  <li>{t.search.helpIds}</li>
                  <li>{t.search.helpWindow}</li>
                </ul>
              </InfoHint>
            </div>
            <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">
              {t.search.structuredHint}
            </p>
            <div className="flex items-center gap-space-sm">
              <div className="relative flex-1">
                <Icon
                  name="terminal"
                  size={18}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-on-surface-variant"
                />
                <input
                  type="text"
                  autoFocus
                  value={wordsInput}
                  onChange={(e) => setWordsInput(e.target.value)}
                  onKeyDown={(e) => handleKeyDown(e, runStructuredSearch)}
                  placeholder={t.search.placeholderStructured}
                  className="w-full bg-surface-container-highest text-on-surface font-code text-code-md pl-9 pr-3 py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
                />
              </div>
              <select
                value={serviceFilter}
                onChange={(e) => setServiceFilter(e.target.value)}
                aria-label={t.search.serviceFilter}
                className="shrink-0 max-w-[12rem] bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary"
              >
                <option value="">{t.search.allServices}</option>
                {serviceNames.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </select>
              <button
                className="shrink-0 flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
                onClick={runStructuredSearch}
                disabled={search.loading && !search.loaded}
                type="button"
              >
                <Icon name="search" size={18} />
                {t.search.searchButton}
              </button>
            </div>
          </>
        ) : (
          <>
            <h2 className="font-headline text-headline-md text-on-surface">{t.search.nlTitle}</h2>
            <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">
              {t.search.nlHint}
            </p>
            <div className="flex items-center gap-space-sm">
              <div className="relative flex-1">
                <Icon
                  name="auto_awesome"
                  size={18}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-secondary"
                />
                <input
                  type="text"
                  autoFocus
                  value={nlQuestion}
                  onChange={(e) => setNlQuestion(e.target.value)}
                  onKeyDown={(e) => handleKeyDown(e, runNlQuery)}
                  placeholder={t.search.placeholderNl}
                  className="w-full bg-surface-container-highest text-on-surface font-body-md text-body-md pl-9 pr-3 py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
                />
              </div>
              <button
                className="shrink-0 flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors disabled:opacity-40"
                onClick={runNlQuery}
                disabled={aiLoading}
                type="button"
              >
                <Icon name="send" size={16} className="text-secondary" />
                {t.search.askButton}
              </button>
            </div>
          </>
        )}
      </div>

      {visibleError && (
        <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
          <Icon name="error" size={18} />
          {visibleError}
        </div>
      )}

      {traceId && <TraceView traceId={traceId} onClose={() => setTraceId(null)} />}

      {showFeed && !feed.loaded && (
        <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <h2 className="font-headline text-headline-md text-on-surface mb-space-sm">{t.search.recentTitle}</h2>
          {feed.error ? (
            <div className="flex items-center gap-space-sm flex-wrap font-body-md text-body-md text-error">
              <Icon name="error" size={18} />
              {t.search.errorPrefix}: {feed.error}
              <button
                type="button"
                className="px-space-sm py-0.5 rounded-lg bg-surface-container-high text-on-surface hover:bg-surface-bright transition-colors"
                onClick={feed.loadFirst}
              >
                {t.search.retry}
              </button>
            </div>
          ) : (
            spinner
          )}
        </div>
      )}

      {showFeed && feed.loaded && (
        <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <div className="flex items-center justify-between mb-space-sm">
            <h2 className="font-headline text-headline-md text-on-surface">{t.search.recentTitle}</h2>
            <span className="font-code text-code-sm text-on-surface-variant bg-surface-container px-space-sm py-0.5 rounded-full">
              {feed.logs.length}
              {feed.hasMore ? "+" : ""}
            </span>
          </div>
          {feed.logs.length === 0 ? (
            empty(t.search.recentEmpty)
          ) : (
            <PagedLogList
              logs={feed.logs}
              hasMore={feed.hasMore}
              loading={feed.loading}
              loadMore={feed.loadMore}
              resetKey="feed"
              view="json"
              onTrace={openTrace}
              onRefresh={feed.refresh}
            />
          )}
        </div>
      )}

      {!showFeed && (
        <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <div className="flex items-center justify-between mb-space-sm">
            <h2 className="font-headline text-headline-md text-on-surface">
              {showAi ? t.search.resultsTitleAi : t.search.resultsTitle}
            </h2>
            <div className="flex items-center gap-space-sm">
              {!resultsLoading && (
                <span className="font-code text-code-sm text-on-surface-variant bg-surface-container px-space-sm py-0.5 rounded-full">
                  {showAi || !search.hasMore
                    ? pluralize(t.search.results_one, t.search.results_other, resultCount)
                    : `${resultCount}+`}
                </span>
              )}
              {viewToggle}
            </div>
          </div>

          {resultsLoading
            ? spinner
            : resultCount === 0
              ? empty(t.search.empty)
              : showAi
                ? (
                  <PagedLogList
                    logs={aiResults ?? []}
                    hasMore={false}
                    loading={false}
                    loadMore={() => undefined}
                    resetKey={`ai-${nlQuestion}`}
                    view={resultsView}
                    onTrace={openTrace}
                  />
                )
                : (
                  <PagedLogList
                    logs={search.logs}
                    hasMore={search.hasMore}
                    loading={search.loading}
                    loadMore={search.loadMore}
                    resetKey={JSON.stringify(structuredQuery)}
                    view={resultsView}
                    onTrace={openTrace}
                  />
                )}
        </div>
      )}
    </>
  );
}
