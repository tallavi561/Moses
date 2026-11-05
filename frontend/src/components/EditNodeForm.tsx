import { useState } from "react";
import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import Modal from "./Modal";
import ConnectionsPicker from "./ConnectionsPicker";
import { probeStatusLabel } from "../probeStatus";
import { Icon } from "../icons";

interface EditNodeFormProps {
  node: ProcessState;
  otherServiceNames: string[];
  initialConnections: string[];
  onSave: (
    serviceName: string,
    status: ProcessState["status"],
    description: string,
    probeUrl: string,
    connections: string[]
  ) => Promise<void>;
  onDelete: () => Promise<void>;
  onClose: () => void;
}

const STATUSES: ProcessState["status"][] = ["UP", "DEGRADED", "DOWN", "UNKNOWN"];

const inputClass =
  "w-full bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant";
const selectClass =
  "bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary cursor-pointer";

export default function EditNodeForm({ node, otherServiceNames, initialConnections, onSave, onDelete, onClose }: EditNodeFormProps) {
  const { t } = useLanguage();
  const [name, setName] = useState(node.serviceName);
  const [status, setStatus] = useState(node.status);
  const [description, setDescription] = useState(node.description ?? "");
  const [probeUrl, setProbeUrl] = useState(node.probeUrl ?? "");
  const [connections, setConnections] = useState<string[]>(initialConnections);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  async function handleSave() {
    const trimmed = name.trim();
    if (!trimmed) {
      setError(t.flow.needsName);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onSave(trimmed, status, description.trim(), probeUrl.trim(), connections);
      onClose();
    } catch {
      setError(t.flow.updateFailed);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
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
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-1.5 font-label-sm text-label-sm text-primary tracking-widest uppercase">
            <Icon name="troubleshoot" size={14} />
            <span>{t.flow.editNodeTitle}</span>
          </div>
          <h2 className="font-headline text-headline-md text-on-surface mt-0.5">{node.serviceName}</h2>
        </div>
      </div>

      <div className="flex items-center gap-space-sm">
        <input
          type="text"
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          className={`${inputClass} flex-1`}
        />
        <select
          className={selectClass}
          value={status}
          onChange={(e) => setStatus(e.target.value as ProcessState["status"])}
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {t.flow.status[s]}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-space-xs">
        <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.nodeDescription}</div>
        <textarea
          className={`${inputClass} min-h-[70px] resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t.flow.nodeDescriptionPlaceholder}
        />
      </div>

      <div className="flex flex-col gap-space-xs">
        <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.probeUrl}</div>
        <p className="font-body-sm text-body-sm text-on-surface-variant">{t.flow.probeUrlHint}</p>
        <input
          type="text"
          value={probeUrl}
          onChange={(e) => setProbeUrl(e.target.value)}
          placeholder={t.flow.probeUrlPlaceholder}
          className={inputClass}
        />
        {probeStatusLabel(node, t) && (
          <p className="flex items-center gap-1 font-code text-code-sm text-on-surface-variant bg-surface-container rounded-lg px-space-sm py-1.5">
            <Icon name="podcasts" size={14} className="text-secondary" />
            {probeStatusLabel(node, t)}
          </p>
        )}
      </div>

      {otherServiceNames.length > 0 && (
        <ConnectionsPicker selected={connections} available={otherServiceNames} onChange={setConnections} />
      )}

      {error && (
        <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
          <Icon name="error" size={18} />
          {error}
        </div>
      )}

      <div className="flex items-center justify-between gap-space-sm pt-space-xs">
        <div className="flex items-center gap-space-sm">
          <button
            className="flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
            onClick={handleSave}
            disabled={submitting}
            type="button"
          >
            <Icon name="check" size={16} />
            {t.flow.save}
          </button>
          <button
            className="px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors disabled:opacity-40"
            onClick={onClose}
            disabled={submitting}
            type="button"
          >
            {t.flow.cancel}
          </button>
        </div>

        {confirmingDelete ? (
          <div className="flex items-center gap-space-xs">
            <span className="font-body-sm text-body-sm text-on-surface-variant">{t.flow.deleteNodeConfirm}</span>
            <button
              className="flex items-center gap-1 px-space-sm py-1.5 rounded-xl bg-error-container text-on-error-container font-headline text-headline-sm transition-colors disabled:opacity-40"
              onClick={handleDelete}
              disabled={submitting}
              type="button"
            >
              {t.flow.delete}
            </button>
            <button
              className="px-space-sm py-1.5 rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
              onClick={() => setConfirmingDelete(false)}
              disabled={submitting}
              type="button"
            >
              {t.flow.cancel}
            </button>
          </div>
        ) : (
          <button
            className="flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-error-container/70 hover:bg-error-container text-on-error-container font-headline text-headline-sm transition-colors disabled:opacity-40"
            onClick={() => setConfirmingDelete(true)}
            disabled={submitting}
            type="button"
          >
            <Icon name="delete_forever" size={16} />
            {t.flow.delete}
          </button>
        )}
      </div>
    </Modal>
  );
}
