import { useLanguage } from "../i18n/LanguageContext";
import { IconGlobe, IconMenu } from "../icons";
import type { SidebarState } from "./useSidebarState";

interface TopBarProps {
  sidebarState: SidebarState;
  onShowSidebar: () => void;
}

export default function TopBar({ sidebarState, onShowSidebar }: TopBarProps) {
  const { lang, toggleLang } = useLanguage();

  return (
    <header className="top-bar">
      <div>
        {sidebarState === "hidden" && (
          <button className="icon-btn" onClick={onShowSidebar} title="Show sidebar">
            <IconMenu />
          </button>
        )}
      </div>

      <button className="lang-toggle" onClick={toggleLang} title="Toggle language">
        <IconGlobe />
        <span>{lang === "en" ? "English" : "עברית"}</span>
      </button>
    </header>
  );
}
