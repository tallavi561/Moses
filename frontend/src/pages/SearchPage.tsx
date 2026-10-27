import { useState } from "react";
import { askAi, searchLogs, type LogEntry } from "../api";
import { useLanguage, pluralize } from "../i18n/LanguageContext";

const LEVEL_LABEL: Record<number, string> = {
  0: "TRACE",
  1: "DEBUG",
  2: "INFO",
  3: "WARN",
  4: "ERROR",
  5: "FATAL",
};

const LEVEL_CLASS: Record<number, string> = {
  0: "badge-muted",
  1: "badge-muted",
  2: "badge-info",
  3: "badge-warn",
  4: "badge-danger",
  5: "badge-danger",
};

function LevelBadge({ level }: { level: number }) {
  return (
    <span className={`badge ${LEVEL_CLASS[level] ?? "badge-muted"}`}>{LEVEL_LABEL[level] ?? String(level)}</span>
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
      <div className="mode-switch" role="radiogroup">
        <button
          type="button"
          className={`mode-option ${searchMode === "structured" ? "active" : ""}`}
          onClick={() => changeSearchMode("structured")}
          aria-pressed={searchMode === "structured"}
        >
          {t.search.structuredTitle}
        </button>
        <button
          type="button"
          className={`mode-option ${searchMode === "ai" ? "active" : ""}`}
          onClick={() => changeSearchMode("ai")}
          aria-pressed={searchMode === "ai"}
        >
          {t.search.nlTitle}
        </button>
      </div>

      {searchMode === "structured" ? (
        <div className="card">
          <h2>{t.search.structuredTitle}</h2>
          <p className="hint">{t.search.structuredHint}</p>
          <div className="search-row">
            <input
              type="text"
              autoFocus
              value={wordsInput}
              onChange={(e) => setWordsInput(e.target.value)}
              onKeyDown={(e) => handleKeyDown(e, runStructuredSearch)}
              placeholder={t.search.placeholderStructured}
            />
            <button className="btn" onClick={runStructuredSearch} disabled={loading}>
              {t.search.searchButton}
            </button>
          </div>
        </div>
      ) : (
        <div className="card">
          <h2>{t.search.nlTitle}</h2>
          <p className="hint">{t.search.nlHint}</p>
          <div className="search-row">
            <input
              type="text"
              autoFocus
              value={nlQuestion}
              onChange={(e) => setNlQuestion(e.target.value)}
              onKeyDown={(e) => handleKeyDown(e, runNlQuery)}
              placeholder={t.search.placeholderNl}
            />
            <button className="btn btn-secondary" onClick={runNlQuery} disabled={loading}>
              {t.search.askButton}
            </button>
          </div>
        </div>
      )}

      {error && <div className="alert-error">{error}</div>}

      {(results !== null || loading) && (
        <div className="card results-card">
          <div className="results-header">
            <h2>{lastMode === "ai" ? t.search.resultsTitleAi : t.search.resultsTitle}</h2>
            {!loading && results !== null && (
              <span className="results-count">
                {pluralize(t.search.results_one, t.search.results_other, results.length)}
              </span>
            )}
          </div>

          {loading || results === null ? (
            <div className="loading-row">{t.search.loading}</div>
          ) : results.length === 0 ? (
            <div className="empty-state">{t.search.empty}</div>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>{t.search.columns.time}</th>
                  <th>{t.search.columns.service}</th>
                  <th>{t.search.columns.level}</th>
                  <th>{t.search.columns.ip}</th>
                  <th>{t.search.columns.message}</th>
                </tr>
              </thead>
              <tbody>
                {results.map((log) => (
                  <tr key={log.logId} className="result-row">
                    <td className="timestamp-cell">
                      {new Date(log.timestamp).toLocaleString(lang === "he" ? "he-IL" : "en-US")}
                    </td>
                    <td>{log.serviceName}</td>
                    <td>
                      <LevelBadge level={log.logLevel} />
                    </td>
                    <td>{log.ip}</td>
                    <td className="message-cell">{log.rawMessage}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </>
  );
}
