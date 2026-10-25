import { useLanguage } from "../i18n/LanguageContext";
import { useTheme } from "../theme/ThemeContext";

export default function SettingsPage() {
  const { t } = useLanguage();
  const { mode, setMode } = useTheme();

  return (
    <div className="card">
      <h2>{t.settings.title}</h2>
      <p className="hint">{t.settings.appearance}</p>

      <div className="mode-switch" role="radiogroup">
        <button
          className={`mode-option ${mode === "dark" ? "active" : ""}`}
          onClick={() => setMode("dark")}
          aria-pressed={mode === "dark"}
        >
          {t.settings.darkMode}
        </button>
        <button
          className={`mode-option ${mode === "light" ? "active" : ""}`}
          onClick={() => setMode("light")}
          aria-pressed={mode === "light"}
        >
          {t.settings.lightMode}
        </button>
      </div>
    </div>
  );
}
