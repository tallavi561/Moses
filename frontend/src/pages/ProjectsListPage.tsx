import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useAuth } from "../auth/AuthContext";

export default function ProjectsListPage() {
  const { projects, selectProject, createProject } = useProjects();
  const { t } = useLanguage();
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);

  function openProject(id: string) {
    selectProject(id);
    navigate(`/p/${id}/search`);
  }

  function handleCreate() {
    const name = newName.trim();
    if (!name) return;
    const project = createProject(name);
    navigate(`/p/${project.id}/search`);
  }

  return (
    <div className="projects-screen">
      <header className="projects-header">
        <div>
          <h1>{t.appName}</h1>
          <p className="hint">{t.projects.subtitle}</p>
        </div>
        {user && (
          <button className="btn-secondary" onClick={signOut}>
            {t.auth.signOut}
          </button>
        )}
      </header>

      <div className="card">
        <h2>{t.projects.title}</h2>
        <ul className="project-list">
          {projects.map((p) => (
            <li key={p.id} className="project-list-item">
              <span>{p.name}</span>
              <button className="btn" onClick={() => openProject(p.id)}>
                {t.projects.open}
              </button>
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h2>{t.projects.newProject}</h2>
        {creating ? (
          <div className="search-row">
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
            <button
              className="btn-secondary"
              onClick={() => {
                setCreating(false);
                setNewName("");
              }}
            >
              {t.projects.cancel}
            </button>
          </div>
        ) : (
          <button className="btn" onClick={() => setCreating(true)}>
            + {t.projects.newProject}
          </button>
        )}
      </div>
    </div>
  );
}
