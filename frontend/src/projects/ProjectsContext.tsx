import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createProject as apiCreateProject, fetchProjects, setApiScope, type Project } from "../api";
import { useAuth } from "../auth/AuthContext";

/**
 * The signed-in user's projects, loaded from auth-service. A project is a team: its members
 * are who can be invited to it and (next) who shares its chat.
 *
 * All monitoring data belongs to one environment of one project, so besides the current
 * project this also tracks the environment being looked at in it (remembered per project).
 * The pair is handed to the API layer, which sends it with every monitoring request.
 */

const CURRENT_KEY = "moses.currentProjectId";
const ENVIRONMENT_KEY_PREFIX = "moses.environment.";

export type { Project };

interface ProjectsContextValue {
  projects: Project[];
  /** True until the first load for this user has finished (successfully or not). */
  loading: boolean;
  error: string | null;
  currentProjectId: string | null;
  currentProject: Project | null;
  /** The environment being looked at in the current project; null only while no project is open. */
  currentEnvironment: string | null;
  selectEnvironment: (name: string) => void;
  /** False for a VIEWER: they can look at monitoring data but not change it. */
  canEdit: boolean;
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

function readEnvironment(projectId: string): string | null {
  try {
    return localStorage.getItem(ENVIRONMENT_KEY_PREFIX + projectId);
  } catch {
    return null;
  }
}

function persistEnvironment(projectId: string, name: string) {
  try {
    localStorage.setItem(ENVIRONMENT_KEY_PREFIX + projectId, name);
  } catch {
    // per-viewer convenience only
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
  // Bumped when the user picks an environment, so the choice (kept in localStorage) is re-read.
  const [, setEnvironmentPick] = useState(0);

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

  // The remembered environment, unless it no longer exists in the project.
  let currentEnvironment: string | null = null;
  if (currentProject) {
    const remembered = readEnvironment(currentProject.id);
    currentEnvironment =
      remembered && currentProject.environments.includes(remembered) ? remembered : currentProject.environments[0];
  }

  // During render, not in an effect: a page's own effects (which fetch) run before this
  // provider's would, and they must already see the scope they are rendered for.
  setApiScope(
    currentProject && currentEnvironment ? { projectId: currentProject.id, environment: currentEnvironment } : null
  );

  const currentId = currentProject?.id ?? null;
  const selectEnvironment = useCallback(
    (name: string) => {
      if (!currentId) return;
      persistEnvironment(currentId, name);
      setEnvironmentPick((n) => n + 1);
    },
    [currentId]
  );

  return (
    <ProjectsContext.Provider
      value={{
        projects,
        loading,
        error,
        currentProjectId,
        currentProject,
        currentEnvironment,
        selectEnvironment,
        canEdit: currentProject !== null && currentProject.role !== "VIEWER",
        selectProject,
        createProject,
        reload,
      }}
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
