import { useEffect, useState } from "react";
import { clearLlm, fetchLlmOverview, saveLlm, type LlmOverview, type LlmView } from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import LlmForm from "./LlmForm";

/**
 * Which AI model the project's agent uses and whose key pays for it: one default for the whole
 * project, and optionally a different one for a single environment. The owner edits; members see
 * the model and the key's last 4 characters.
 */
export default function AiModelSettings() {
  const { currentProject } = useProjects();
  const { t } = useLanguage();
  const projectId = currentProject?.id ?? null;
  const isOwner = currentProject?.role === "OWNER";

  const [overview, setOverview] = useState<LlmOverview | null>(null);
  const [failed, setFailed] = useState(false);
  /** "project", an environment name, or null when no form is open. */
  const [editing, setEditing] = useState<string | null>(null);

  useEffect(() => {
    setOverview(null);
    setFailed(false);
    setEditing(null);
    if (!projectId) return;
    let cancelled = false;
    fetchLlmOverview(projectId)
      .then((o) => !cancelled && setOverview(o))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [projectId, currentProject?.environments.join(",")]);

  if (!currentProject || !projectId) return null;

  async function remove(environment?: string) {
    if (!projectId) return;
    const question = environment ? t.ai.useDefaultConfirm.replace("{env}", environment) : t.ai.removeConfirm;
    if (!window.confirm(question)) return;
    try {
      setOverview(await clearLlm(projectId, environment));
    } catch {
      setFailed(true);
    }
  }

  return (
    <div className="card">
      <h2 className="font-headline text-headline-md text-on-surface">{t.ai.title}</h2>
      <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.ai.hint}</p>

      {failed && <p className="font-body-sm text-body-sm text-error mb-space-sm">{t.ai.loadFailed}</p>}
      {!overview && !failed && <p className="font-body-sm text-body-sm text-on-surface-variant">{t.ai.loading}</p>}
      {overview && !overview.canStoreKeys && (
        <p className="font-body-sm text-body-sm text-error mb-space-sm">{t.ai.serverNotReady}</p>
      )}

      {overview && (
        <div className="flex flex-col gap-space-sm">
          {/* Project default */}
          <div className="px-space-md py-space-sm rounded-xl bg-surface-container flex flex-col gap-space-sm">
            <div className="flex items-center justify-between gap-space-md">
              <div className="min-w-0">
                <div className="font-headline text-headline-sm text-on-surface">{t.ai.projectDefault}</div>
                <ModelLine view={overview.project} empty={t.ai.notSet} />
              </div>
              {isOwner && editing !== "project" && (
                <div className="flex items-center gap-space-sm shrink-0">
                  <button className="px-space-sm py-1 btn btn-secondary font-body-sm text-body-sm" onClick={() => setEditing("project")} type="button">
                    {overview.project ? t.ai.change : t.ai.set}
                  </button>
                  {overview.project && (
                    <button className="text-on-surface-variant hover:text-error p-1 rounded-lg" onClick={() => void remove()} title={t.ai.remove} type="button">
                      <Icon name="delete" size={18} />
                    </button>
                  )}
                </div>
              )}
            </div>
            {editing === "project" && (
              <LlmForm
                current={overview.project}
                onCancel={() => setEditing(null)}
                onSave={async (input) => {
                  setOverview(await saveLlm(projectId, input));
                  setEditing(null);
                }}
              />
            )}
          </div>

          {/* Per environment */}
          {Object.entries(overview.environments).map(([env, view]) => (
            <div key={env} className="px-space-md py-space-sm rounded-xl bg-surface-container flex flex-col gap-space-sm">
              <div className="flex items-center justify-between gap-space-md">
                <div className="min-w-0">
                  <div className="flex items-center gap-space-xs">
                    <Icon name="layers" size={16} className="text-secondary" />
                    <span className="font-code text-code-md text-on-surface">{env}</span>
                  </div>
                  {view ? (
                    <ModelLine view={view} empty="" ownLabel={t.ai.ownKey} />
                  ) : (
                    <span className="font-body-sm text-body-sm text-on-surface-variant">
                      {overview.project ? t.ai.usesDefault : t.ai.usesDefaultNotSet}
                    </span>
                  )}
                </div>
                {isOwner && editing !== env && (
                  <div className="flex items-center gap-space-sm shrink-0">
                    <button className="px-space-sm py-1 btn btn-secondary font-body-sm text-body-sm" onClick={() => setEditing(env)} type="button">
                      {view ? t.ai.change : t.ai.useOwnKey}
                    </button>
                    {view && (
                      <button className="px-space-sm py-1 font-body-sm text-body-sm text-on-surface-variant hover:text-on-surface" onClick={() => void remove(env)} type="button">
                        {t.ai.useDefault}
                      </button>
                    )}
                  </div>
                )}
              </div>
              {editing === env && (
                <LlmForm
                  current={view}
                  onCancel={() => setEditing(null)}
                  onSave={async (input) => {
                    setOverview(await saveLlm(projectId, input, env));
                    setEditing(null);
                  }}
                />
              )}
            </div>
          ))}
        </div>
      )}

      {!isOwner && <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-md">{t.ai.ownerOnly}</p>}
    </div>
  );
}

function ModelLine({ view, empty, ownLabel }: { view: LlmView | null; empty: string; ownLabel?: string }) {
  if (!view) return <span className="font-body-sm text-body-sm text-on-surface-variant">{empty}</span>;
  return (
    <span className="font-body-sm text-body-sm text-on-surface-variant flex flex-wrap items-center gap-x-space-sm">
      {ownLabel && <span className="text-secondary">{ownLabel}</span>}
      <span className="font-code text-code-sm text-on-surface">{view.model}</span>
      <span>{view.provider === "anthropic" ? "Anthropic" : view.baseUrl}</span>
      <span className="font-code text-code-sm">····{view.keyHint}</span>
    </span>
  );
}
