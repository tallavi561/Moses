import { useState } from "react";
import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import ConnectionsPicker from "./ConnectionsPicker";
import { Icon } from "../icons";

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

const inputClass =
  "w-full bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant";
const selectClass =
  "bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary cursor-pointer";

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
      <button
        className="self-start flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all shadow-md"
        onClick={() => setOpen(true)}
        type="button"
      >
        <Icon name="add_circle" size={18} />
        {t.flow.addService}
      </button>
    );
  }

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg flex flex-col gap-space-md">
      <div>
        <h2 className="font-headline text-headline-md text-on-surface">{t.flow.addServiceTitle}</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs">{t.flow.addServiceHint}</p>
      </div>

      <div className="flex items-center gap-space-sm">
        <input
          type="text"
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleSubmit()}
          placeholder={t.flow.serviceNamePlaceholder}
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
      </div>

      {existingServices.length > 0 && (
        <ConnectionsPicker selected={calls} available={existingServices} onChange={setCalls} />
      )}

      {error && (
        <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
          <Icon name="error" size={18} />
          {error}
        </div>
      )}

      <div className="flex items-center gap-space-sm">
        <button
          className="flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
          onClick={handleSubmit}
          disabled={submitting}
          type="button"
        >
          <Icon name="check" size={16} />
          {t.flow.add}
        </button>
        <button
          className="flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors disabled:opacity-40"
          onClick={reset}
          disabled={submitting}
          type="button"
        >
          {t.flow.cancel}
        </button>
      </div>
    </div>
  );
}
