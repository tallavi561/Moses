import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createProject as apiCreateProject, fetchProjects, type Project } from "../api";
import { useAuth } from "../auth/AuthContext";

/**
 * The signed-in user's projects, loaded from auth-service. A project is a team: its members
 * are who can be invited to it and (next) who shares its chat.
 *
 * Still true: logs/architecture/search data has no projectId yet, so every project currently
 * shows the same monitoring data. Scoping that data per project is a separate backend change.
 */

const CURRENT_KEY = "moses.currentProjectId";

export type { Project };

interface ProjectsContextValue {
  projects: Project[];
  /** True until the first load for this user has finished (successfully or not). */
  loading: boolean;
  error: string | null;
  currentProjectId: string | null;
  currentProject: Project | null;
  selectProject: (id: string) => void;
  createProject: (name: string) => Promise<Project>;
  reload: () => Promise<void>;
}

const ProjectsContext = createContext<ProjectsContextValue | null>(null);

function readCurrentId(): string | null {
  try {
    return localStorage.getItem(CURRENT_KEY);
  } catch {
    return null;
  }
}

function persistCurrentId(id: string) {
  try {
    localStorage.setItem(CURRENT_KEY, id);
  } catch {
    // per-viewer convenience only
  }
}

export function ProjectsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const [projects, setProjects] = useState<Project[]>([]);
  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [currentProjectId, setCurrentProjectId] = useState<string | null>(readCurrentId);

  const reload = useCallback(async () => {
    if (!userId) return;
    try {
      const loaded = await fetchProjects();
      setProjects(loaded);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadedFor(userId);
    }
  }, [userId]);

  useEffect(() => {
    if (!userId) {
      // Signed out: never leave one user's projects on screen for the next one.
      setProjects([]);
      setLoadedFor(null);
      setError(null);
      return;
    }
    void reload();
  }, [userId, reload]);

  const selectProject = useCallback((id: string) => {
    setCurrentProjectId(id);
    persistCurrentId(id);
  }, []);

  const createProject = useCallback(
    async (name: string) => {
      const project = await apiCreateProject(name);
      setProjects((prev) => [...prev, project]);
      selectProject(project.id);
      return project;
    },
    [selectProject]
  );

  const currentProject = projects.find((p) => p.id === currentProjectId) ?? null;
  const loading = userId !== null && loadedFor !== userId;

  return (
    <ProjectsContext.Provider
      value={{ projects, loading, error, currentProjectId, currentProject, selectProject, createProject, reload }}
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
