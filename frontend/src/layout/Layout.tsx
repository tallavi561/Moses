import { useEffect } from "react";
import { Navigate, Outlet, useParams } from "react-router-dom";
import Sidebar from "./Sidebar";
import TopBar from "./TopBar";
import { useSidebarState } from "./useSidebarState";
import { useProjects } from "../projects/ProjectsContext";

const OFFSET: Record<string, string> = {
  expanded: "pl-72",
  collapsed: "pl-20",
  hidden: "pl-0",
};

export default function Layout() {
  const sidebar = useSidebarState();
  const { projectId } = useParams<{ projectId: string }>();
  const { projects, selectProject } = useProjects();
  const projectExists = projects.some((p) => p.id === projectId);

  useEffect(() => {
    if (projectId && projectExists) selectProject(projectId);
  }, [projectId, projectExists, selectProject]);

  if (!projectId || !projectExists) {
    return <Navigate to="/projects" replace />;
  }

  return (
    <div className="min-h-screen bg-background text-on-surface">
      <Sidebar state={sidebar.state} onCollapseToggle={sidebar.collapseToggle} onHide={sidebar.hide} />
      <div className={`transition-[padding] duration-200 ${OFFSET[sidebar.state]}`}>
        <TopBar sidebarState={sidebar.state} onShowSidebar={sidebar.show} />
        <main className="w-full pt-16 min-h-screen">
          <div className="max-w-[1400px] mx-auto px-space-md py-space-md flex flex-col gap-space-md">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
