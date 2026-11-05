import { useLanguage } from "../i18n/LanguageContext";
import { useTheme } from "../theme/ThemeContext";
import { Icon } from "../icons";

export default function SettingsPage() {
  const { t } = useLanguage();
  const { mode, setMode } = useTheme();

  return (
    <>
      <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
        <h2 className="font-headline text-headline-md text-on-surface">{t.settings.title}</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.settings.appearance}</p>

        <div className="flex items-center bg-surface-container-high rounded-xl p-0.5 shadow-inner w-fit" role="radiogroup">
          <button
            className={`flex items-center gap-1.5 px-space-md py-1.5 rounded-lg font-headline text-headline-sm transition-all ${
              mode === "dark" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
            }`}
            onClick={() => setMode("dark")}
            aria-pressed={mode === "dark"}
            type="button"
          >
            <Icon name="dark_mode" size={16} />
            {t.settings.darkMode}
          </button>
          <button
            className={`flex items-center gap-1.5 px-space-md py-1.5 rounded-lg font-headline text-headline-sm transition-all ${
              mode === "light" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
            }`}
            onClick={() => setMode("light")}
            aria-pressed={mode === "light"}
            type="button"
          >
            <Icon name="light_mode" size={16} />
            {t.settings.lightMode}
          </button>
        </div>
      </div>

      <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
        <h2 className="font-headline text-headline-md text-on-surface">{t.settings.integrationTitle}</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.settings.integrationHint}</p>
        <a
          className="inline-flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all shadow-md"
          href="/moses-monitoring-skill.md"
          download
        >
          <Icon name="download" size={18} />
          {t.settings.downloadSkill}
        </a>
      </div>
    </>
  );
}
