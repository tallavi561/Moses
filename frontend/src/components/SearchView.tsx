import { useState } from "react";
import { askAi, searchLogs, type LogEntry } from "../api";

const LEVEL_INFO: Record<number, { label: string; className: string }> = {
  0: { label: "TRACE", className: "badge-muted" },
  1: { label: "DEBUG", className: "badge-muted" },
  2: { label: "INFO", className: "badge-info" },
  3: { label: "WARN", className: "badge-warn" },
  4: { label: "ERROR", className: "badge-danger" },
  5: { label: "FATAL", className: "badge-danger" },
};

function LevelBadge({ level }: { level: number }) {
  const info = LEVEL_INFO[level] ?? { label: String(level), className: "badge-muted" };
  return <span className={`badge ${info.className}`}>{info.label}</span>;
}

export default function SearchView() {
  const [wordsInput, setWordsInput] = useState("");
  const [nlQuestion, setNlQuestion] = useState("");
  const [results, setResults] = useState<LogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [lastMode, setLastMode] = useState<"structured" | "ai" | null>(null);

  async function runStructuredSearch() {
    const words = wordsInput.split(/\s+/).filter(Boolean);
    if (words.length === 0) {
      setError("יש להזין לפחות מילת חיפוש אחת");
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
      setError(`החיפוש נכשל: ${(e as Error).message}`);
      setResults(null);
    } finally {
      setLoading(false);
    }
  }

  async function runNlQuery() {
    if (!nlQuestion.trim()) {
      setError("יש להזין שאלה");
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
      setError(`שאילתת ה-AI נכשלה: ${(e as Error).message}`);
      setResults(null);
    } finally {
      setLoading(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent, action: () => void) {
    if (e.key === "Enter") action();
  }

  return (
    <>
      <div className="card">
        <h2>חיפוש מובנה</h2>
        <p className="hint">חיפוש AND של מילים ב-24 השעות האחרונות, על פני כל הלוגים</p>
        <div className="search-row">
          <input
            type="text"
            value={wordsInput}
            onChange={(e) => setWordsInput(e.target.value)}
            onKeyDown={(e) => handleKeyDown(e, runStructuredSearch)}
            placeholder="לדוגמה: failed token"
          />
          <button className="btn" onClick={runStructuredSearch} disabled={loading}>
            חפש
          </button>
        </div>
      </div>

      <div className="card">
        <h2>שאלה בשפה חופשית</h2>
        <p className="hint">שירות ה-AI יפרש את השאלה ויריץ חיפוש מתאים</p>
        <div className="search-row">
          <input
            type="text"
            value={nlQuestion}
            onChange={(e) => setNlQuestion(e.target.value)}
            onKeyDown={(e) => handleKeyDown(e, runNlQuery)}
            placeholder="לדוגמה: תראה לי שגיאות בשירות ה-auth"
          />
          <button className="btn btn-secondary" onClick={runNlQuery} disabled={loading}>
            שאל
          </button>
        </div>
      </div>

      {error && <div className="alert-error">{error}</div>}

      {(results !== null || loading) && (
        <div className="card results-card">
          <div className="results-header">
            <h2>{lastMode === "ai" ? "תוצאות (AI)" : "תוצאות חיפוש"}</h2>
            {!loading && results !== null && (
              <span className="results-count">
                {results.length} {results.length === 1 ? "תוצאה" : "תוצאות"}
              </span>
            )}
          </div>

          {loading || results === null ? (
            <div className="loading-row">טוען...</div>
          ) : results.length === 0 ? (
            <div className="empty-state">לא נמצאו לוגים התואמים לחיפוש</div>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>זמן</th>
                  <th>שירות</th>
                  <th>רמה</th>
                  <th>IP</th>
                  <th>הודעה</th>
                </tr>
              </thead>
              <tbody>
                {results.map((log) => (
                  <tr key={log.logId}>
                    <td className="timestamp-cell">{new Date(log.timestamp).toLocaleString("he-IL")}</td>
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
