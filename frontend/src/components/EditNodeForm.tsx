import { useState } from "react";
import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import Modal from "./Modal";

interface EditNodeFormProps {
  node: ProcessState;
  otherServiceNames: string[];
  onSave: (serviceName: string, status: ProcessState["status"], connections: string[]) => Promise<void>;
  onDelete: () => Promise<void>;
  onClose: () => void;
}

const STATUSES: ProcessState["status"][] = ["UP", "DEGRADED", "DOWN", "UNKNOWN"];

export default function EditNodeForm({ node, otherServiceNames, onSave, onDelete, onClose }: EditNodeFormProps) {
  const { t } = useLanguage();
  const [name, setName] = useState(node.serviceName);
  const [status, setStatus] = useState(node.status);
  const [connections, setConnections] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function toggleConnection(service: string) {
    setConnections((prev) => (prev.includes(service) ? prev.filter((s) => s !== service) : [...prev, service]));
  }

  async function handleSave() {
    const trimmed = name.trim();
    if (!trimmed) {
      setError(t.flow.needsName);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onSave(trimmed, status, connections);
      onClose();
    } catch {
      setError(t.flow.updateFailed);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (!window.confirm(t.flow.deleteNodeConfirm)) return;
    setSubmitting(true);
    setError(null);
    try {
      await onDelete();
      onClose();
    } catch {
      setError(t.flow.deleteFailed);
      setSubmitting(false);
    }
  }

  return (
    <Modal onClose={onClose}>
      <h2>{t.flow.editNodeTitle}</h2>

      <div className="search-row">
        <input type="text" autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        <select className="status-select" value={status} onChange={(e) => setStatus(e.target.value as ProcessState["status"])}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {t.flow.status[s]}
            </option>
          ))}
        </select>
      </div>

      {otherServiceNames.length > 0 && (
        <div className="calls-picker">
          <div className="calls-label">{t.flow.callsLabel}</div>
          <p className="hint">{t.flow.callsHint}</p>
          <div className="calls-options">
            {otherServiceNames.map((service) => (
              <label key={service} className="calls-option">
                <input type="checkbox" checked={connections.includes(service)} onChange={() => toggleConnection(service)} />
                {service}
              </label>
            ))}
          </div>
        </div>
      )}

      {error && <div className="alert-error">{error}</div>}

      <div className="search-row" style={{ marginTop: 14, justifyContent: "space-between" }}>
        <div className="search-row">
          <button className="btn" onClick={handleSave} disabled={submitting}>
            {t.flow.save}
          </button>
          <button className="btn-secondary" onClick={onClose} disabled={submitting}>
            {t.flow.cancel}
          </button>
        </div>
        <button className="btn-danger" onClick={handleDelete} disabled={submitting}>
          {t.flow.delete}
        </button>
      </div>
    </Modal>
  );
}
