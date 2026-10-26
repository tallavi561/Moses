import { useState } from "react";
import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";

interface AddServiceFormProps {
  existingServices: string[];
  onAdd: (serviceName: string, status: ProcessState["status"], calls: string[]) => Promise<void>;
}

const STATUSES: ProcessState["status"][] = ["UP", "DEGRADED", "DOWN", "UNKNOWN"];

export default function AddServiceForm({ existingServices, onAdd }: AddServiceFormProps) {
  const { t } = useLanguage();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [status, setStatus] = useState<ProcessState["status"]>("UP");
  const [calls, setCalls] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function toggleCall(service: string) {
    setCalls((prev) => (prev.includes(service) ? prev.filter((s) => s !== service) : [...prev, service]));
  }

  function reset() {
    setName("");
    setStatus("UP");
    setCalls([]);
    setError(null);
    setOpen(false);
  }

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed) {
      setError(t.flow.needsName);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onAdd(trimmed, status, calls);
      reset();
    } catch {
      setError(t.flow.addFailed);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) {
    return (
      <div className="card">
        <button className="btn" onClick={() => setOpen(true)}>
          + {t.flow.addService}
        </button>
      </div>
    );
  }

  return (
    <div className="card">
      <h2>{t.flow.addServiceTitle}</h2>
      <p className="hint">{t.flow.addServiceHint}</p>

      <div className="search-row">
        <input
          type="text"
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleSubmit()}
          placeholder={t.flow.serviceNamePlaceholder}
        />
        <select className="status-select" value={status} onChange={(e) => setStatus(e.target.value as ProcessState["status"])}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {t.flow.status[s]}
            </option>
          ))}
        </select>
      </div>

      {existingServices.length > 0 && (
        <div className="calls-picker">
          <div className="calls-label">{t.flow.callsLabel}</div>
          <p className="hint">{t.flow.callsHint}</p>
          <div className="calls-options">
            {existingServices.map((service) => (
              <label key={service} className="calls-option">
                <input type="checkbox" checked={calls.includes(service)} onChange={() => toggleCall(service)} />
                {service}
              </label>
            ))}
          </div>
        </div>
      )}

      {error && <div className="alert-error">{error}</div>}

      <div className="search-row" style={{ marginTop: 14 }}>
        <button className="btn" onClick={handleSubmit} disabled={submitting}>
          {t.flow.add}
        </button>
        <button className="btn-secondary" onClick={reset} disabled={submitting}>
          {t.flow.cancel}
        </button>
      </div>
    </div>
  );
}
