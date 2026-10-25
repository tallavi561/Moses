import { useState } from "react";
import { askAi, searchLogs, type LogEntry } from "../api";

export default function SearchView() {
  const [wordsInput, setWordsInput] = useState("");
  const [nlQuestion, setNlQuestion] = useState("");
  const [results, setResults] = useState<LogEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function runStructuredSearch() {
    setLoading(true);
    setError(null);
    try {
      const words = wordsInput.split(/\s+/).filter(Boolean);
      const to = new Date().toISOString();
      const from = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
      const res = await searchLogs(words, from, to);
      setResults(res);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function runNlQuery() {
    setLoading(true);
    setError(null);
    try {
      const res = await askAi(nlQuestion);
      const typed = res as { results?: LogEntry[] };
      setResults(typed.results ?? []);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <section>
        <h2>Structured search (last 24h, AND of words)</h2>
        <input
          value={wordsInput}
          onChange={(e) => setWordsInput(e.target.value)}
          placeholder="e.g. failed token"
        />
        <button onClick={runStructuredSearch} disabled={loading}>
          Search
        </button>
      </section>

      <section>
        <h2>Natural language query (AI service)</h2>
        <input
          value={nlQuestion}
          onChange={(e) => setNlQuestion(e.target.value)}
          placeholder="e.g. show errors in auth-service"
        />
        <button onClick={runNlQuery} disabled={loading}>
          Ask
        </button>
      </section>

      {error && <p style={{ color: "red" }}>{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Service</th>
            <th>Level</th>
            <th>IP</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {results.map((log) => (
            <tr key={log.logId}>
              <td>{new Date(log.timestamp).toLocaleString()}</td>
              <td>{log.serviceName}</td>
              <td>{log.logLevel}</td>
              <td>{log.ip}</td>
              <td>{log.rawMessage}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
