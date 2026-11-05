import { useState } from "react";
import type { Connection, ConnectionDirection } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import Modal from "./Modal";
import { Icon } from "../icons";

interface EditConnectionFormProps {
  connection: Connection;
  onSave: (direction: ConnectionDirection, communicationType: string, description: string) => Promise<void>;
  onDelete: () => Promise<void>;
  onClose: () => void;
}

const inputClass =
  "w-full bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant";
const selectClass =
  "w-full bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary cursor-pointer";

export default function EditConnectionForm({ connection, onSave, onDelete, onClose }: EditConnectionFormProps) {
  const { t } = useLanguage();
  const [direction, setDirection] = useState<ConnectionDirection>(connection.direction);
  const [communicationType, setCommunicationType] = useState(connection.communicationType ?? "");
  const [description, setDescription] = useState(connection.description ?? "");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

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
      <div className="flex items-center gap-1.5 font-label-sm text-label-sm text-primary tracking-widest uppercase">
        <Icon name="swap_horiz" size={14} />
        <span>{t.flow.editConnectionTitle}</span>
      </div>
      <h2 className="font-headline text-headline-md text-on-surface -mt-space-sm">
        {connection.sourceService} <Icon name="arrow_forward" size={16} className="inline text-on-surface-variant" />{" "}
        {connection.targetService}
      </h2>

      <div className="flex flex-col gap-space-xs">
        <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.direction}</div>
        <select className={selectClass} value={direction} onChange={(e) => setDirection(e.target.value as ConnectionDirection)}>
          <option value="SOURCE_TO_TARGET">{directionLabel("SOURCE_TO_TARGET")}</option>
          <option value="TARGET_TO_SOURCE">{directionLabel("TARGET_TO_SOURCE")}</option>
          <option value="BIDIRECTIONAL">{directionLabel("BIDIRECTIONAL")}</option>
        </select>
      </div>

      <div className="flex flex-col gap-space-xs">
        <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.communicationType}</div>
        <input
          type="text"
          value={communicationType}
          onChange={(e) => setCommunicationType(e.target.value)}
          placeholder={t.flow.communicationTypePlaceholder}
          className={inputClass}
        />
      </div>

      <div className="flex flex-col gap-space-xs">
        <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.description}</div>
        <textarea
          className={`${inputClass} min-h-[70px] resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t.flow.descriptionPlaceholder}
        />
      </div>

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
