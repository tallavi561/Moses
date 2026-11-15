import { useEffect, useState, type FormEvent } from "react";
import {
  addEnvironment,
  ApiError,
  createApiKey,
  fetchApiKeys,
  removeEnvironment,
  revokeApiKey,
  type ApiKeyInfo,
  type CreatedApiKey,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/**
 * The current project's environments and, per environment, the API keys its services report in
 * with. Owner only: a key is what lets data into the project.
 */
export default function EnvironmentSettings() {
  const { currentProject, reload } = useProjects();
  const { t } = useLanguage();
  const projectId = currentProject?.id ?? null;
  const isOwner = currentProject?.role === "OWNER";

  const [keys, setKeys] = useState<ApiKeyInfo[] | null>(null);
  const [newName, setNewName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // The one moment a key's value exists on screen: right after it was created.
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setKeys(null);
    setCreated(null);
    setError(null);
    if (!projectId || !isOwner) return;
    let cancelled = false;
    fetchApiKeys(projectId)
      .then((loaded) => {
        if (!cancelled) setKeys(loaded);
      })
      .catch(() => {
        if (!cancelled) setError(t.environments.loadFailed);
      });
    return () => {
      cancelled = true;
    };
    // Not on t: refetching the keys because the language changed would be pointless.
  }, [projectId, isOwner]);

  if (!currentProject || !projectId) return null;

  /** Runs one change, showing `failure` (or the 409 message) if it does not go through. */
  async function run(change: () => Promise<void>, failure: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await change();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? t.environments.nameTaken : failure);
    } finally {
      setBusy(false);
    }
  }

  function handleAdd(event: FormEvent) {
    event.preventDefault();
    const name = newName.trim().toLowerCase();
    if (!name || !projectId) return;
    void run(async () => {
      await addEnvironment(projectId, name);
      setNewName("");
      await reload();
    }, t.environments.addFailed);
  }

  function handleRemove(name: string) {
    if (!projectId || !window.confirm(t.environments.removeConfirm.replace("{name}", name))) return;
    void run(async () => {
      await removeEnvironment(projectId, name);
      setKeys(await fetchApiKeys(projectId));
      await reload();
    }, t.environments.removeFailed);
  }

  function handleCreateKey(environment: string) {
    if (!projectId) return;
    setCopied(false);
    void run(async () => {
      const key = await createApiKey(projectId, environment);
      setCreated(key);
      setKeys(await fetchApiKeys(projectId));
    }, t.environments.keyFailed);
  }

  function handleRevoke(key: ApiKeyInfo) {
    if (!projectId || !window.confirm(t.environments.revokeConfirm.replace("{prefix}", key.prefix))) return;
    void run(async () => {
      setKeys(await revokeApiKey(projectId, key.id));
      if (created?.id === key.id) setCreated(null);
    }, t.environments.keyFailed);
  }

  async function handleCopy() {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.key);
      setCopied(true);
    } catch {
      // clipboard blocked: the key is on screen and selectable
    }
  }

  const environments = currentProject.environments;

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
      <h2 className="font-headline text-headline-md text-on-surface">{t.environments.title}</h2>
      <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">
        {t.environments.hint}
      </p>

      <ul className="flex flex-col gap-space-sm">
        {environments.map((name) => {
          const environmentKeys = (keys ?? []).filter((k) => k.environment === name);
          return (
            <li key={name} className="px-space-md py-space-sm rounded-xl bg-surface-container flex flex-col gap-space-sm">
              <div className="flex items-center justify-between gap-space-md">
                <span className="font-code text-code-md text-on-surface">{name}</span>
                {isOwner && (
                  <div className="flex items-center gap-space-sm">
                    <button
                      className="flex items-center gap-1 px-space-sm py-1 rounded-lg bg-surface-container-high hover:bg-surface-bright text-on-surface font-body-sm text-body-sm transition-colors disabled:opacity-40"
                      onClick={() => handleCreateKey(name)}
                      disabled={busy}
                      type="button"
                    >
                      <Icon name="key" size={16} className="text-secondary" />
                      {t.environments.createKey}
                    </button>
                    {environments.length > 1 && (
                      <button
                        className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-lg disabled:opacity-40"
                        onClick={() => handleRemove(name)}
                        disabled={busy}
                        title={t.environments.remove}
                        type="button"
                      >
                        <Icon name="delete" size={18} />
                      </button>
                    )}
                  </div>
                )}
              </div>

              {created && created.environment === name && (
                <div className="flex flex-col gap-space-xs">
                  <span className="font-body-sm text-body-sm text-secondary">{t.environments.keyShownOnce}</span>
                  <div className="flex items-center gap-space-sm">
                    <input
                      className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-code text-code-sm px-space-md py-space-sm rounded-xl focus:outline-none"
                      aria-label={t.environments.createKey}
                      onFocus={(e) => e.target.select()}
                      readOnly
                      value={created.key}
                    />
                    <button
                      className="shrink-0 flex items-center gap-1 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
                      onClick={handleCopy}
                      type="button"
                    >
                      <Icon name={copied ? "check" : "content_copy"} size={16} />
                      {copied ? t.team.copied : t.team.copy}
                    </button>
                  </div>
                </div>
              )}

              {isOwner && environmentKeys.length > 0 && (
                <ul className="flex flex-col gap-1">
                  {environmentKeys.map((key) => (
                    <li key={key.id} className="flex items-center justify-between gap-space-md">
                      <span className="font-code text-code-sm text-on-surface-variant">
                        {key.prefix}… · {new Date(key.createdAt).toLocaleDateString()}
                      </span>
                      <button
                        className="font-body-sm text-body-sm text-on-surface-variant hover:text-error transition-colors disabled:opacity-40"
                        onClick={() => handleRevoke(key)}
                        disabled={busy}
                        type="button"
                      >
                        {t.environments.revoke}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {isOwner && keys && environmentKeys.length === 0 && (
                <span className="font-body-sm text-body-sm text-on-surface-variant">{t.environments.noKeys}</span>
              )}
            </li>
          );
        })}
      </ul>

      {isOwner ? (
        <form className="mt-space-md flex flex-col gap-space-xs" onSubmit={handleAdd}>
          <label className="font-body-sm text-body-sm text-on-surface-variant" htmlFor="environment-add-name">
            {t.environments.add}
          </label>
          <div className="flex items-center gap-space-sm">
            <input
              id="environment-add-name"
              className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-code text-code-sm px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder={t.environments.namePlaceholder}
              maxLength={30}
              pattern="[A-Za-z0-9][A-Za-z0-9\-]*"
              title={t.environments.nameRule}
            />
            <button
              className="shrink-0 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
              disabled={!newName.trim() || busy}
              type="submit"
            >
              {t.team.add}
            </button>
          </div>
        </form>
      ) : (
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-md">{t.environments.ownerOnly}</p>
      )}

      {error && (
        <p className="font-body-sm text-body-sm text-error mt-space-sm" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
