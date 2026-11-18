import { useLanguage } from "../i18n/LanguageContext";
import { useTheme } from "../theme/ThemeContext";
import { Icon } from "../icons";
import TeamSettings from "../components/TeamSettings";
import RepoSettings from "../components/RepoSettings";
import AiModelSettings from "../components/AiModelSettings";
import EnvironmentSettings from "../components/EnvironmentSettings";
import { API_BASE_URL } from "../api";

/**
 * The skill file has a {{MOSES_BASE_URL}} placeholder; fill in this instance's gateway so the
 * downloaded copy points at the right place. Falls back to the plain link if the fetch fails.
 */
async function downloadSkill(event: React.MouseEvent<HTMLAnchorElement>) {
  event.preventDefault();
  const href = event.currentTarget.href;
  try {
    const res = await fetch(href);
    if (!res.ok) throw new Error(String(res.status));
    const text = (await res.text()).split("{{MOSES_BASE_URL}}").join(API_BASE_URL.replace(/\/+$/, ""));
    const url = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "moses-monitoring-skill.md";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch {
    window.location.href = href;
  }
}

export default function SettingsPage() {
  const { t } = useLanguage();
  const { mode, setMode } = useTheme();

  return (
    <>
      <div className="card">
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

      <RepoSettings />

      <AiModelSettings />

      <TeamSettings />

      <EnvironmentSettings />

      <div className="card">
        <h2 className="font-headline text-headline-md text-on-surface">{t.settings.integrationTitle}</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.settings.integrationHint}</p>
        <a
          className="inline-flex items-center gap-1.5 px-space-md py-space-sm btn btn-primary shadow-md"
          href="/moses-monitoring-skill.md"
          download
          onClick={downloadSkill}
        >
          <Icon name="download" size={18} />
          {t.settings.downloadSkill}
        </a>
      </div>
    </>
  );
}
