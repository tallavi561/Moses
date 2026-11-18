import { useEffect, useState, type FormEvent } from "react";
import { ApiError, setProjectRepo } from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/** The GitHub repository the project's chat agent reads. The owner sets it; everyone sees it. */
export default function RepoSettings() {
  const { currentProject, reload } = useProjects();
  const { t } = useLanguage();
  const isOwner = currentProject?.role === "OWNER";
  const [value, setValue] = useState(currentProject?.repoUrl ?? "");
  const [state, setState] = useState<"idle" | "saving" | "saved" | "invalid" | "failed">("idle");

  useEffect(() => {
    setValue(currentProject?.repoUrl ?? "");
    setState("idle");
  }, [currentProject?.id, currentProject?.repoUrl]);

  if (!currentProject) return null;

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!currentProject) return;
    setState("saving");
    try {
      await setProjectRepo(currentProject.id, value.trim());
      await reload();
      setState("saved");
    } catch (e) {
      setState(e instanceof ApiError && e.status === 400 ? "invalid" : "failed");
    }
  }

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
      <h2 className="font-headline text-headline-md text-on-surface">{t.repo.title}</h2>
      <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.repo.hint}</p>

      {isOwner ? (
        <form className="flex flex-col gap-space-xs" onSubmit={save}>
          <div className="flex items-center gap-space-sm">
            <input
              className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-code text-code-sm px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
              dir="ltr"
              type="url"
              value={value}
              onChange={(e) => {
                setValue(e.target.value);
                setState("idle");
              }}
              placeholder="https://github.com/owner/repo"
              aria-label={t.repo.title}
            />
            <button
              className="shrink-0 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
              disabled={state === "saving" || value.trim() === (currentProject.repoUrl ?? "")}
              type="submit"
            >
              {t.repo.save}
            </button>
          </div>
          {state === "saved" && <p className="font-body-sm text-body-sm text-secondary">{t.repo.saved}</p>}
          {state === "invalid" && <p className="font-body-sm text-body-sm text-error">{t.repo.invalid}</p>}
          {state === "failed" && <p className="font-body-sm text-body-sm text-error">{t.repo.failed}</p>}
          <p className="font-body-sm text-body-sm text-on-surface-variant">{t.repo.publicOnly}</p>
        </form>
      ) : currentProject.repoUrl ? (
        <a className="inline-flex items-center gap-1.5 text-secondary underline underline-offset-2" href={currentProject.repoUrl} rel="noreferrer noopener" target="_blank">
          <Icon name="code" size={16} />
          {currentProject.repoUrl}
        </a>
      ) : (
        <p className="font-body-sm text-body-sm text-on-surface-variant">{t.repo.none}</p>
      )}
    </div>
  );
}
