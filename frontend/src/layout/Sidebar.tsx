import { NavLink } from "react-router-dom";
import { useLanguage } from "../i18n/LanguageContext";
import { IconChevron, IconHide } from "../icons";
import { NAV_ITEMS } from "./navItems";
import type { SidebarState } from "./useSidebarState";

interface SidebarProps {
  state: SidebarState;
  onCollapseToggle: () => void;
  onHide: () => void;
}

export default function Sidebar({ state, onCollapseToggle, onHide }: SidebarProps) {
  const { t } = useLanguage();
  const expanded = state === "expanded";

  if (state === "hidden") return null;

  return (
    <aside className={`sidebar ${expanded ? "expanded" : "collapsed"}`}>
      <div className="sidebar-top">
        {expanded && (
          <div className="sidebar-brand">
            <div className="logo">MS</div>
            <div>
              <div className="brand-name">{t.appName}</div>
              <div className="brand-tagline">{t.appTagline}</div>
            </div>
          </div>
        )}
        <button className="icon-btn" onClick={onCollapseToggle} title={expanded ? "Collapse" : "Expand"}>
          <IconChevron direction={expanded ? "start" : "end"} />
        </button>
      </div>

      <nav className="sidebar-nav">
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            className={({ isActive }) => `sidebar-link ${isActive ? "active" : ""}`}
            title={!expanded ? t.nav[item.labelKey] : undefined}
          >
            <item.icon />
            {expanded && <span>{t.nav[item.labelKey]}</span>}
            {expanded && !item.enabled && <span className="soon-dot" title="Coming soon" />}
          </NavLink>
        ))}
      </nav>

      <button className="hide-sidebar-btn" onClick={onHide} title="Hide sidebar">
        <IconHide />
        {expanded && <span>Hide</span>}
      </button>
    </aside>
  );
}
