import { createContext, useContext, useState, type ReactNode } from "react";

/**
 * MOCK project data. Mongo has no projectId field on anything yet, so
 * switching "projects" here does NOT actually scope logs/flow/search data -
 * it only exists so the UI (switcher, projects list) can be built and
 * tested now. Wiring real per-project data isolation is a backend change
 * for later (see chat).
 */

const PROJECTS_KEY = "moses.projects";
const CURRENT_KEY = "moses.currentProjectId";

export interface Project {
  id: string;
  name: string;
}

const SEED_PROJECTS: Project[] = [{ id: "demo", name: "Demo project" }];

interface ProjectsContextValue {
  projects: Project[];
  currentProjectId: string | null;
  currentProject: Project | null;
  selectProject: (id: string) => void;
  createProject: (name: string) => Project;
}

const ProjectsContext = createContext<ProjectsContextValue | null>(null);

function readProjects(): Project[] {
  try {
    const stored = localStorage.getItem(PROJECTS_KEY);
    if (stored) {
      const parsed = JSON.parse(stored);
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch {
    // fall through to seed
  }
  return SEED_PROJECTS;
}

function readCurrentId(projects: Project[]): string | null {
  try {
    const stored = localStorage.getItem(CURRENT_KEY);
    if (stored && projects.some((p) => p.id === stored)) return stored;
  } catch {
    // fall through
  }
  return null;
}

function persistProjects(projects: Project[]) {
  try {
    localStorage.setItem(PROJECTS_KEY, JSON.stringify(projects));
  } catch {
    // ignore
  }
}

function persistCurrentId(id: string | null) {
  try {
    if (id) localStorage.setItem(CURRENT_KEY, id);
    else localStorage.removeItem(CURRENT_KEY);
  } catch {
    // ignore
  }
}

export function ProjectsProvider({ children }: { children: ReactNode }) {
  const [projects, setProjects] = useState<Project[]>(readProjects);
  const [currentProjectId, setCurrentProjectId] = useState<string | null>(() => readCurrentId(readProjects()));

  function selectProject(id: string) {
    setCurrentProjectId(id);
    persistCurrentId(id);
  }

  function createProject(name: string): Project {
    const project: Project = { id: crypto.randomUUID(), name };
    const next = [...projects, project];
    setProjects(next);
    persistProjects(next);
    selectProject(project.id);
    return project;
  }

  const currentProject = projects.find((p) => p.id === currentProjectId) ?? null;

  return (
    <ProjectsContext.Provider
      value={{ projects, currentProjectId, currentProject, selectProject, createProject }}
    >
      {children}
    </ProjectsContext.Provider>
  );
}

export function useProjects() {
  const ctx = useContext(ProjectsContext);
  if (!ctx) throw new Error("useProjects must be used within ProjectsProvider");
  return ctx;
}
