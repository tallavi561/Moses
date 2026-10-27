import { useState } from "react";
import type { Connection, ConnectionDirection } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import Modal from "./Modal";

interface EditConnectionFormProps {
  connection: Connection;
  onSave: (direction: ConnectionDirection, communicationType: string, description: string) => Promise<void>;
  onDelete: () => Promise<void>;
  onClose: () => void;
}

export default function EditConnectionForm({ connection, onSave, onDelete, onClose }: EditConnectionFormProps) {
  const { t } = useLanguage();
  const [direction, setDirection] = useState<ConnectionDirection>(connection.direction);
  const [communicationType, setCommunicationType] = useState(connection.communicationType ?? "");
  const [description, setDescription] = useState(connection.description ?? "");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const directionLabel = (d: ConnectionDirection) => {
    const template =
      d === "SOURCE_TO_TARGET"
        ? t.flow.directionSourceToTarget
        : d === "TARGET_TO_SOURCE"
        ? t.flow.directionTargetToSource
        : t.flow.directionBidirectional;
    return template.replace("{source}", connection.sourceService).replace("{target}", connection.targetService);
  };

  async function handleSave() {
    setSubmitting(true);
    setError(null);
    try {
      await onSave(direction, communicationType.trim(), description.trim());
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
      <h2>{t.flow.editConnectionTitle}</h2>

      <div className="calls-picker">
        <div className="calls-label">{t.flow.direction}</div>
        <select
          className="status-select"
          style={{ width: "100%" }}
          value={direction}
          onChange={(e) => setDirection(e.target.value as ConnectionDirection)}
        >
          <option value="SOURCE_TO_TARGET">{directionLabel("SOURCE_TO_TARGET")}</option>
          <option value="TARGET_TO_SOURCE">{directionLabel("TARGET_TO_SOURCE")}</option>
          <option value="BIDIRECTIONAL">{directionLabel("BIDIRECTIONAL")}</option>
        </select>
      </div>

      <div className="calls-picker">
        <div className="calls-label">{t.flow.communicationType}</div>
        <input
          type="text"
          value={communicationType}
          onChange={(e) => setCommunicationType(e.target.value)}
          placeholder={t.flow.communicationTypePlaceholder}
        />
      </div>

      <div className="calls-picker">
        <div className="calls-label">{t.flow.description}</div>
        <textarea
          className="connection-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t.flow.descriptionPlaceholder}
        />
      </div>

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
