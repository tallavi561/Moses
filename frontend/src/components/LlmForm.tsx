import { useState, type FormEvent } from "react";
import type { LlmInput, LlmProvider, LlmView } from "../api";
import { useLanguage } from "../i18n/LanguageContext";

const DEFAULT_MODEL: Record<LlmProvider, string> = {
  anthropic: "claude-opus-5-5",
  "openai-compatible": "",
};

const FIELD =
  "w-full min-w-0 bg-surface-container-highest text-on-surface font-code text-code-sm px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant";

interface LlmFieldsProps {
  value: LlmInput;
  onChange: (value: LlmInput) => void;
  /** The setting being replaced: lets the key field stay empty to keep the stored key. */
  current?: LlmView | null;
}

/** Provider, model, (API address) and key - shared by the project default and environment overrides. */
export function LlmFields({ value, onChange, current }: LlmFieldsProps) {
  const { t } = useLanguage();
  const keepsKey = Boolean(current && current.provider === value.provider);

  return (
    <div className="flex flex-col gap-space-sm">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-space-sm">
        <label className="flex flex-col gap-1">
          <span className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.provider}</span>
          <select
            className={FIELD}
            value={value.provider}
            onChange={(e) => {
              const provider = e.target.value as LlmProvider;
              onChange({ ...value, provider, model: DEFAULT_MODEL[provider] || value.model });
            }}
          >
            <option value="anthropic">Anthropic (Claude)</option>
            <option value="openai-compatible">{t.ai.openAiCompatible}</option>
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.model}</span>
          <input
            className={FIELD}
            dir="ltr"
            value={value.model}
            onChange={(e) => onChange({ ...value, model: e.target.value })}
            placeholder={value.provider === "anthropic" ? "claude-opus-5-5" : "gpt-4o"}
            required
          />
        </label>
      </div>
      {value.provider === "openai-compatible" && (
        <label className="flex flex-col gap-1">
          <span className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.baseUrl}</span>
          <input
            className={FIELD}
            dir="ltr"
            type="url"
            value={value.baseUrl ?? ""}
            onChange={(e) => onChange({ ...value, baseUrl: e.target.value })}
            placeholder="https://api.openai.com/v1"
            required
          />
        </label>
      )}
      <label className="flex flex-col gap-1">
        <span className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.apiKey}</span>
        <input
          className={FIELD}
          dir="ltr"
          type="password"
          autoComplete="off"
          value={value.apiKey}
          onChange={(e) => onChange({ ...value, apiKey: e.target.value })}
          placeholder={keepsKey ? t.ai.keepKey.replace("{hint}", current!.keyHint) : value.provider === "anthropic" ? "sk-ant-..." : "sk-..."}
          required={!keepsKey}
        />
      </label>
    </div>
  );
}

export function emptyLlmInput(current?: LlmView | null): LlmInput {
  return current
    ? { provider: current.provider, model: current.model, apiKey: "", baseUrl: current.baseUrl ?? "" }
    : { provider: "anthropic", model: DEFAULT_MODEL.anthropic, apiKey: "", baseUrl: "" };
}

interface LlmFormProps {
  current?: LlmView | null;
  onSave: (input: LlmInput) => Promise<void>;
  onCancel?: () => void;
}

/** The fields plus save/cancel, with the provider's own reason shown when it rejects the key. */
export default function LlmForm({ current, onSave, onCancel }: LlmFormProps) {
  const { t } = useLanguage();
  const [value, setValue] = useState<LlmInput>(() => emptyLlmInput(current));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSave({ ...value, model: value.model.trim(), apiKey: value.apiKey.trim(), baseUrl: value.baseUrl?.trim() });
    } catch (e) {
      setError(e instanceof Error ? e.message : t.ai.saveFailed);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="flex flex-col gap-space-sm" onSubmit={submit}>
      <LlmFields value={value} onChange={setValue} current={current} />
      {error && (
        <p className="font-body-sm text-body-sm text-error" role="alert">
          {error}
        </p>
      )}
      <div className="flex items-center gap-space-sm">
        <button className="px-space-md py-space-sm btn btn-primary disabled:opacity-40" disabled={busy} type="submit">
          {busy ? t.ai.checking : t.ai.save}
        </button>
        {onCancel && (
          <button className="px-space-md py-space-sm btn btn-secondary" onClick={onCancel} type="button">
            {t.ai.cancel}
          </button>
        )}
        <span className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.checkedNote}</span>
      </div>
    </form>
  );
}
