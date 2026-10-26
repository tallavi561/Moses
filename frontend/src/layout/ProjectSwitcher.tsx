import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useOnClickOutside } from "./useOnClickOutside";

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
    <div className="project-switcher" ref={ref}>
      <button className="project-switcher-trigger" onClick={() => setOpen((v) => !v)} title={t.projects.switchProject}>
        <div className="logo">MS</div>
        {expanded && (
          <>
            <div className="project-switcher-label">
              <div className="brand-name">{currentProject?.name ?? t.appName}</div>
              <div className="brand-tagline">{t.appTagline}</div>
            </div>
            <span className="project-switcher-chevron">▾</span>
          </>
        )}
      </button>

      {open && (
        <div className="project-switcher-menu">
          <div className="project-switcher-menu-title">{t.projects.switchProject}</div>
          {otherProjects.length === 0 ? (
            <div className="project-switcher-empty">{t.projects.noOtherProjects}</div>
          ) : (
            otherProjects.map((p) => (
              <button key={p.id} className="project-switcher-item" onClick={() => switchTo(p.id)}>
                {p.name}
              </button>
            ))
          )}
          <div className="project-switcher-divider" />
          {creating ? (
            <div className="project-switcher-create">
              <input
                type="text"
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleCreate()}
                placeholder={t.projects.namePlaceholder}
              />
              <button className="btn" onClick={handleCreate} disabled={!newName.trim()}>
                {t.projects.create}
              </button>
            </div>
          ) : (
            <button className="project-switcher-item" onClick={() => setCreating(true)}>
              + {t.projects.newProject}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
