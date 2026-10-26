import { useEffect } from "react";
import { Navigate, Outlet, useParams } from "react-router-dom";
import Sidebar from "./Sidebar";
import TopBar from "./TopBar";
import { useSidebarState } from "./useSidebarState";
import { useProjects } from "../projects/ProjectsContext";

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
    <div className="shell">
      <Sidebar state={sidebar.state} onCollapseToggle={sidebar.collapseToggle} onHide={sidebar.hide} />
      <div className="content-area">
        <TopBar sidebarState={sidebar.state} onShowSidebar={sidebar.show} />
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
