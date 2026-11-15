import { useRef, useState } from "react";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useOnClickOutside } from "./useOnClickOutside";
import { Icon } from "../icons";

/**
 * Which environment of the current project is on screen. Everything the pages show - logs,
 * search results, the state of each service - is that environment's only.
 */
export default function EnvironmentSwitcher() {
  const { currentProject, currentEnvironment, selectEnvironment } = useProjects();
  const { t } = useLanguage();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useOnClickOutside(ref, () => setOpen(false));

  if (!currentProject || !currentEnvironment) return null;

  const single = currentProject.environments.length === 1;

  return (
    <div className="relative" ref={ref}>
      <button
        className="flex items-center gap-space-xs px-space-sm py-1 rounded-xl bg-surface-container hover:bg-surface-container-high text-on-surface font-code text-code-sm transition-colors disabled:hover:bg-surface-container"
        onClick={() => setOpen((v) => !v)}
        title={t.environments.switchEnvironment}
        disabled={single}
        type="button"
      >
        <Icon name="layers" size={16} className="text-secondary" />
        <span>{currentEnvironment}</span>
        {!single && (
          <Icon name="expand_more" size={16} className={`transition-transform ${open ? "rotate-180" : ""}`} />
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-[calc(100%+8px)] w-48 bg-surface-container-low rounded-2xl shadow-2xl p-space-sm z-50 space-y-1">
          <div className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
            {t.environments.switchEnvironment}
          </div>
          {currentProject.environments.map((name) => (
            <button
              key={name}
              className={`w-full text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high font-code text-code-sm transition-colors flex items-center justify-between ${
                name === currentEnvironment ? "text-secondary" : "text-on-surface"
              }`}
              onClick={() => {
                selectEnvironment(name);
                setOpen(false);
              }}
              type="button"
            >
              {name}
              {name === currentEnvironment && <Icon name="check" size={16} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
