import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useAuth } from "../auth/AuthContext";
import { Icon } from "../icons";
import HeroLogo from "../components/HeroLogo";

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
    <div className="min-h-screen w-full bg-background px-space-md py-space-xl">
      <div className="max-w-3xl mx-auto flex flex-col gap-space-lg">
        <HeroLogo compact />
        <header className="flex items-center justify-between gap-space-md">
          <div className="flex items-center gap-space-sm">
            <div>
              <h1 className="font-headline text-headline-lg text-on-surface">{t.appName}</h1>
              <p className="font-body-md text-body-md text-on-surface-variant">{t.projects.subtitle}</p>
            </div>
          </div>
          {user && (
            <button
              className="flex items-center gap-1 px-space-md py-space-xs rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors shrink-0"
              onClick={signOut}
              type="button"
            >
              <Icon name="logout" size={16} />
              {t.auth.signOut}
            </button>
          )}
        </header>

        <section className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <h2 className="font-headline text-headline-md text-on-surface mb-space-sm">{t.projects.title}</h2>
          {projects.length === 0 ? (
            <p className="font-body-md text-body-md text-on-surface-variant">{t.projects.noOtherProjects}</p>
          ) : (
            <ul className="flex flex-col gap-space-xs">
              {projects.map((p) => (
                <li
                  key={p.id}
                  className="flex items-center justify-between gap-space-md px-space-md py-space-sm rounded-xl bg-surface-container hover:bg-surface-container-high transition-colors"
                >
                  <div className="flex items-center gap-space-sm min-w-0">
                    <Icon name="deployed_code" size={20} className="text-secondary shrink-0" />
                    <span className="font-body-lg text-body-lg text-on-surface truncate">{p.name}</span>
                  </div>
                  <button
                    className="shrink-0 flex items-center gap-1 px-space-md py-1.5 rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all"
                    onClick={() => openProject(p.id)}
                    type="button"
                  >
                    {t.projects.open}
                    <Icon name="arrow_forward" size={16} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
          <h2 className="font-headline text-headline-md text-on-surface mb-space-sm">{t.projects.newProject}</h2>
          {creating ? (
            <div className="flex items-center gap-space-sm">
              <input
                type="text"
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleCreate()}
                placeholder={t.projects.namePlaceholder}
                className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
              />
              <button
                className="shrink-0 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
                onClick={handleCreate}
                disabled={!newName.trim()}
                type="button"
              >
                {t.projects.create}
              </button>
              <button
                className="shrink-0 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
                onClick={() => {
                  setCreating(false);
                  setNewName("");
                }}
                type="button"
              >
                {t.projects.cancel}
              </button>
            </div>
          ) : (
            <button
              className="flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
              onClick={() => setCreating(true)}
              type="button"
            >
              <Icon name="add_circle" size={18} className="text-secondary" />
              {t.projects.newProject}
            </button>
          )}
        </section>
      </div>
    </div>
  );
}
