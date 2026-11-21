import { Fragment, useEffect, useState } from "react";
import { Navigate, Outlet, useLocation, useParams } from "react-router-dom";
import Sidebar from "./Sidebar";
import TopBar from "./TopBar";
import { useSidebarState } from "./useSidebarState";
import { useIsMobile } from "./useIsMobile";
import { useProjects } from "../projects/ProjectsContext";

const OFFSET: Record<string, string> = {
  expanded: "pl-72",
  collapsed: "pl-20",
  hidden: "pl-0",
};

export default function Layout() {
  const sidebar = useSidebarState();
  // On a phone the sidebar is a drawer over the content, closed by default (the desktop
  // expanded/collapsed/hidden choice is kept for desktop).
  const mobile = useIsMobile();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setDrawerOpen(false), [location.pathname, mobile]);
  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawerOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);
  const { projectId } = useParams<{ projectId: string }>();
  const { projects, loading, selectProject, currentProjectId, currentEnvironment } = useProjects();
  const projectExists = projects.some((p) => p.id === projectId);

  useEffect(() => {
    if (projectId && projectExists) selectProject(projectId);
  }, [projectId, projectExists, selectProject]);

  // Projects come from the server now: wait for them before deciding the id is unknown.
  if (loading) {
    return <div className="min-h-screen bg-background" />;
  }

  if (!projectId || !projectExists) {
    return <Navigate to="/projects" replace />;
  }

  return (
    <div className="min-h-screen bg-background text-on-surface">
      {mobile ? (
        <>
          {drawerOpen && (
            <div className="fixed inset-0 z-40 bg-black/50" onClick={() => setDrawerOpen(false)} aria-hidden="true" />
          )}
          <Sidebar state="expanded" mobileOpen={drawerOpen} onCollapseToggle={() => undefined} onHide={() => setDrawerOpen(false)} />
        </>
      ) : (
        <Sidebar state={sidebar.state} onCollapseToggle={sidebar.collapseToggle} onHide={sidebar.hide} />
      )}
      <div className={`transition-[padding] duration-200 ${mobile ? "pl-0" : OFFSET[sidebar.state]}`}>
        <TopBar
          sidebarState={mobile ? "hidden" : sidebar.state}
          onShowSidebar={mobile ? () => setDrawerOpen(true) : sidebar.show}
        />
        <main className="w-full pt-16 min-h-screen">
          <div className="max-w-[1400px] mx-auto px-space-sm sm:px-space-md py-space-md flex flex-col gap-space-md">
            {/* Only once the URL's project is the current one (so requests carry its scope), and
                remounted when the project or environment changes: every page loads its data on
                mount, and none of it carries over to another project or environment. */}
            {currentProjectId === projectId && (
              <Fragment key={`${projectId}:${currentEnvironment}`}>
                <Outlet />
              </Fragment>
            )}
          </div>
        </main>
      </div>
    </div>
  );
}
