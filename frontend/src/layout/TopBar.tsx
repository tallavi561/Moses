import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import UserMenu from "./UserMenu";
import type { SidebarState } from "./useSidebarState";

interface TopBarProps {
  sidebarState: SidebarState;
  onShowSidebar: () => void;
}

const LEFT: Record<SidebarState, string> = {
  expanded: "left-72",
  collapsed: "left-20",
  hidden: "left-0",
};

export default function TopBar({ sidebarState, onShowSidebar }: TopBarProps) {
  const { lang, toggleLang } = useLanguage();

  return (
    <header
      className={`fixed top-0 right-0 h-16 z-40 bg-surface-container-lowest/80 backdrop-blur-xl shadow-[0_1px_8px_rgba(0,0,0,0.3)] transition-[left] duration-200 ${LEFT[sidebarState]}`}
    >
      <div className="h-16 w-full px-space-md flex items-center justify-between gap-space-md">
        <div>
          {sidebarState === "hidden" && (
            <button
              className="text-on-surface-variant hover:text-on-surface transition-colors p-space-xs rounded-lg hover:bg-surface-container-high"
              onClick={onShowSidebar}
              title="Show sidebar"
              type="button"
            >
              <Icon name="menu" size={20} />
            </button>
          )}
        </div>

        <div className="flex items-center gap-space-md">
          <button
            className="flex items-center gap-space-xs px-space-sm py-1 rounded-xl bg-surface-container hover:bg-surface-container-high text-on-surface font-code text-code-sm transition-colors"
            onClick={toggleLang}
            title="Toggle language"
            type="button"
          >
            <Icon name="language" size={16} />
            <span>{lang === "en" ? "English" : "עברית"}</span>
          </button>
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
