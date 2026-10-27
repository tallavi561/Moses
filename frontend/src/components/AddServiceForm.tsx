import { useState } from "react";
import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import ConnectionsPicker from "./ConnectionsPicker";

interface AddServiceFormProps {
  existingServices: string[];
  onAdd: (
    serviceName: string,
    status: ProcessState["status"],
    description: string,
    probeUrl: string,
    calls: string[]
  ) => Promise<void>;
}

const STATUSES: ProcessState["status"][] = ["UP", "DEGRADED", "DOWN", "UNKNOWN"];

export default function AddServiceForm({ existingServices, onAdd }: AddServiceFormProps) {
  const { t } = useLanguage();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [status, setStatus] = useState<ProcessState["status"]>("UP");
  const [description, setDescription] = useState("");
  const [probeUrl, setProbeUrl] = useState("");
  const [calls, setCalls] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function reset() {
    setName("");
    setStatus("UP");
    setDescription("");
    setProbeUrl("");
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
      await onAdd(trimmed, status, description.trim(), probeUrl.trim(), calls);
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

      <div className="calls-picker">
        <div className="calls-label">{t.flow.nodeDescription}</div>
        <textarea
          className="connection-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t.flow.nodeDescriptionPlaceholder}
        />
      </div>

      <div className="calls-picker">
        <div className="calls-label">{t.flow.probeUrl}</div>
        <p className="hint">{t.flow.probeUrlHint}</p>
        <input
          type="text"
          value={probeUrl}
          onChange={(e) => setProbeUrl(e.target.value)}
          placeholder={t.flow.probeUrlPlaceholder}
        />
      </div>

      {existingServices.length > 0 && (
        <ConnectionsPicker selected={calls} available={existingServices} onChange={setCalls} />
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
