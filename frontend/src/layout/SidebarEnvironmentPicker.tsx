import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/**
 * The environment being worked in, right under the project - like a namespace under a project in
 * OpenShift. Everything on screen (architecture, logs, chat, the agent) belongs to it.
 */
export default function SidebarEnvironmentPicker({ expanded }: { expanded: boolean }) {
  const { currentProject, currentEnvironment, selectEnvironment } = useProjects();
  const { t } = useLanguage();
  if (!currentProject || !currentEnvironment) return null;

  if (!expanded) {
    return (
      <div
        className="mx-auto px-1.5 py-0.5 rounded-md bg-surface-container-high text-secondary font-code text-[11px] max-w-full truncate"
        title={`${t.environments.switchEnvironment}: ${currentEnvironment}`}
      >
        {currentEnvironment}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1 px-space-sm">
      <span className="font-label-sm text-label-sm text-on-surface-variant uppercase tracking-wide">
        {t.environments.switchEnvironment}
      </span>
      <div className="flex flex-wrap gap-1" role="radiogroup" aria-label={t.environments.switchEnvironment}>
        {currentProject.environments.map((name) => {
          const active = name === currentEnvironment;
          return (
            <button
              key={name}
              role="radio"
              aria-checked={active}
              className={`flex items-center gap-1 px-space-sm py-1 rounded-lg font-code text-code-sm transition-colors ${
                active
                  ? "bg-secondary/15 text-secondary ring-1 ring-secondary/40"
                  : "bg-surface-container text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface"
              }`}
              onClick={() => selectEnvironment(name)}
              type="button"
            >
              <Icon name="layers" size={14} />
              {name}
            </button>
          );
        })}
      </div>
    </div>
  );
}
