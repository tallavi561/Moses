import { useState } from "react";
import { askAi, searchLogs, type LogEntry } from "../api";
import { useLanguage, pluralize } from "../i18n/LanguageContext";
import { Icon } from "../icons";

const LEVEL_LABEL: Record<number, string> = {
  0: "TRACE",
  1: "DEBUG",
  2: "INFO",
  3: "WARN",
  4: "ERROR",
  5: "FATAL",
};

const LEVEL_CLASS: Record<number, string> = {
  0: "bg-surface-container-high text-on-surface-variant",
  1: "bg-surface-container-high text-on-surface-variant",
  2: "bg-secondary-container/20 text-secondary",
  3: "bg-tertiary-container/20 text-tertiary",
  4: "bg-primary-container/20 text-primary",
  5: "bg-primary-container text-on-primary-container",
};

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

type SearchMode = "structured" | "ai";

const SEARCH_MODE_KEY = "moses.search.mode";

export default function SearchPage() {
  const { t, lang } = useLanguage();
  const [searchMode, setSearchMode] = useState<SearchMode>(() => {
    try {
      return localStorage.getItem(SEARCH_MODE_KEY) === "ai" ? "ai" : "structured";
    } catch {
      return "structured";
    }
  });
  const [wordsInput, setWordsInput] = useState("");
  const [nlQuestion, setNlQuestion] = useState("");
  const [results, setResults] = useState<LogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [lastMode, setLastMode] = useState<"structured" | "ai" | null>(null);

  async function runStructuredSearch() {
    const words = wordsInput.split(/\s+/).filter(Boolean);
    if (words.length === 0) {
      setError(t.search.needsWord);
      return;
    }
    setLoading(true);
    setError(null);
    setLastMode("structured");
    try {
      const to = new Date().toISOString();
      const from = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
      const res = await searchLogs(words, from, to);
      setResults(res);
    } catch (e) {
      setError(`${t.search.errorPrefix}: ${(e as Error).message}`);
      setResults(null);
    } finally {
      setLoading(false);
    }
  }

  async function runNlQuery() {
    if (!nlQuestion.trim()) {
      setError(t.search.needsQuestion);
      return;
    }
    setLoading(true);
    setError(null);
    setLastMode("ai");
    try {
      const res = await askAi(nlQuestion);
      const typed = res as { results?: LogEntry[] };
      setResults(typed.results ?? []);
    } catch (e) {
      setError(`${t.search.aiErrorPrefix}: ${(e as Error).message}`);
      setResults(null);
    } finally {
      setLoading(false);
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
            <h2 className="font-headline text-headline-md text-on-surface">{t.search.structuredTitle}</h2>
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
              <button
                className="shrink-0 flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
                onClick={runStructuredSearch}
                disabled={loading}
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
                disabled={loading}
                type="button"
              >
                <Icon name="send" size={16} className="text-secondary" />
                {t.search.askButton}
              </button>
            </div>
          </>
        )}
      </div>

      {error && (
        <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
          <Icon name="error" size={18} />
          {error}
        </div>
      )}

      {(results !== null || loading) && (
        <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <div className="flex items-center justify-between mb-space-sm">
            <h2 className="font-headline text-headline-md text-on-surface">
              {lastMode === "ai" ? t.search.resultsTitleAi : t.search.resultsTitle}
            </h2>
            {!loading && results !== null && (
              <span className="font-code text-code-sm text-on-surface-variant bg-surface-container px-space-sm py-0.5 rounded-full">
                {pluralize(t.search.results_one, t.search.results_other, results.length)}
              </span>
            )}
          </div>

          {loading || results === null ? (
            <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
              <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
              {t.search.loading}
            </div>
          ) : results.length === 0 ? (
            <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
              <Icon name="search_off" size={28} />
              <span className="font-body-md text-body-md">{t.search.empty}</span>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-xl">
              <table className="w-full border-collapse">
                <thead>
                  <tr className="text-left">
                    <th className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                      {t.search.columns.time}
                    </th>
                    <th className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                      {t.search.columns.service}
                    </th>
                    <th className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                      {t.search.columns.level}
                    </th>
                    <th className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                      {t.search.columns.ip}
                    </th>
                    <th className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
                      {t.search.columns.message}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((log) => (
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
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </>
  );
}
