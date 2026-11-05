import { NavLink } from "react-router-dom";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { NAV_ITEMS } from "./navItems";
import ProjectSwitcher from "./ProjectSwitcher";
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
    <aside
      className={`fixed left-0 top-0 h-full bg-surface-container-lowest/90 backdrop-blur-xl z-50 flex flex-col justify-between p-space-md shadow-[0_1px_8px_rgba(0,0,0,0.3)] transition-[width] duration-200 ${
        expanded ? "w-72" : "w-20"
      }`}
    >
      <div className="flex flex-col gap-space-lg overflow-hidden">
        <div className="flex items-center justify-between px-space-sm py-space-xs">
          <ProjectSwitcher expanded={expanded} />
          {expanded && (
            <button
              className="text-on-surface-variant hover:text-on-surface transition-colors p-space-xs rounded-lg hover:bg-surface-container-high shrink-0"
              onClick={onCollapseToggle}
              title="Collapse"
              type="button"
            >
              <Icon name="menu_open" size={18} />
            </button>
          )}
        </div>

        {!expanded && (
          <button
            className="mx-auto text-on-surface-variant hover:text-on-surface transition-colors p-space-xs rounded-lg hover:bg-surface-container-high"
            onClick={onCollapseToggle}
            title="Expand"
            type="button"
          >
            <Icon name="menu" size={18} />
          </button>
        )}

        <nav className="flex flex-col gap-space-xs">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              className={({ isActive }) =>
                `group flex items-center justify-between px-space-md py-space-sm rounded-xl transition-all ${
                  isActive
                    ? "bg-primary-container text-on-primary-container font-semibold"
                    : "text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface"
                }`
              }
              title={!expanded ? t.nav[item.labelKey] : undefined}
            >
              <div className="flex items-center gap-space-sm min-w-0">
                <Icon name={item.icon} size={20} className="shrink-0" />
                {expanded && <span className="font-body text-body-md truncate">{t.nav[item.labelKey]}</span>}
              </div>
              {expanded && !item.enabled && (
                <span className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" title="Coming soon" />
              )}
            </NavLink>
          ))}
        </nav>
      </div>

      <button
        className="flex items-center gap-space-sm px-space-md py-space-sm rounded-xl text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface transition-all"
        onClick={onHide}
        title="Hide sidebar"
        type="button"
      >
        <Icon name="left_panel_close" size={20} className="shrink-0" />
        {expanded && <span className="font-body text-body-md">Hide</span>}
      </button>
    </aside>
  );
}
