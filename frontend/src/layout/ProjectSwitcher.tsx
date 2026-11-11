import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useOnClickOutside } from "./useOnClickOutside";
import { Icon } from "../icons";
import AnimatedLogo from "../components/AnimatedLogo";

interface ProjectSwitcherProps {
  expanded: boolean;
}

export default function ProjectSwitcher({ expanded }: ProjectSwitcherProps) {
  const { projects, currentProject, selectProject, createProject } = useProjects();
  const { t } = useLanguage();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  useOnClickOutside(ref, () => {
    setOpen(false);
    setCreating(false);
  });

  function switchTo(id: string) {
    selectProject(id);
    navigate(`/p/${id}/search`);
    setOpen(false);
  }

  function handleCreate() {
    const name = newName.trim();
    if (!name) return;
    const project = createProject(name);
    navigate(`/p/${project.id}/search`);
    setNewName("");
    setCreating(false);
    setOpen(false);
  }

  const otherProjects = projects.filter((p) => p.id !== currentProject?.id);

  return (
    <div className="relative min-w-0" ref={ref}>
      <button
        className="group flex items-center gap-space-sm min-w-0 text-left"
        onClick={() => setOpen((v) => !v)}
        title={t.projects.switchProject}
        type="button"
      >
        <AnimatedLogo size={40} />
        {expanded && (
          <>
            <div className="min-w-0 flex-1">
              <div className="font-headline text-headline-sm text-on-surface truncate">
                {currentProject?.name ?? t.appName}
              </div>
              <div className="font-code text-code-sm text-secondary uppercase tracking-widest truncate">
                {t.appTagline}
              </div>
            </div>
            <Icon
              name="expand_more"
              size={18}
              className={`text-on-surface-variant shrink-0 transition-transform ${open ? "rotate-180" : ""}`}
            />
          </>
        )}
      </button>

      {open && (
        <div className="absolute left-0 top-[calc(100%+8px)] w-60 bg-surface-container-low rounded-2xl shadow-2xl p-space-sm z-50 space-y-1">
          <div className="font-label-sm text-label-sm text-on-surface-variant uppercase px-space-sm py-space-xs">
            {t.projects.switchProject}
          </div>
          {otherProjects.length === 0 ? (
            <div className="px-space-sm py-space-xs font-body-sm text-body-sm text-on-surface-variant">
              {t.projects.noOtherProjects}
            </div>
          ) : (
            otherProjects.map((p) => (
              <button
                key={p.id}
                className="w-full text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high text-on-surface font-body-md text-body-md transition-colors"
                onClick={() => switchTo(p.id)}
                type="button"
              >
                {p.name}
              </button>
            ))
          )}
          <div className="h-px bg-outline-variant my-1" />
          {creating ? (
            <div className="flex flex-col gap-space-xs px-space-xs">
              <input
                type="text"
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleCreate()}
                placeholder={t.projects.namePlaceholder}
                className="w-full min-w-0 bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-1.5 rounded-lg focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
              />
              <button
                className="px-space-sm py-1.5 rounded-lg bg-primary-container text-on-primary-container font-headline text-headline-sm disabled:opacity-40"
                onClick={handleCreate}
                disabled={!newName.trim()}
                type="button"
              >
                {t.projects.create}
              </button>
            </div>
          ) : (
            <button
              className="w-full text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high text-secondary font-body-md text-body-md transition-colors flex items-center gap-1"
              onClick={() => setCreating(true)}
              type="button"
            >
              <Icon name="add_circle" size={16} />
              {t.projects.newProject}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
