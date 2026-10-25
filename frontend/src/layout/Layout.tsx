import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar";
import TopBar from "./TopBar";
import { useSidebarState } from "./useSidebarState";

export default function Layout() {
  const sidebar = useSidebarState();

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
