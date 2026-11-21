import { useState } from "react";
import { useLanguage } from "../i18n/LanguageContext";
import { useTheme } from "../theme/ThemeContext";
import { Icon } from "../icons";
import TeamSettings from "../components/TeamSettings";
import RepoSettings from "../components/RepoSettings";
import AiModelSettings from "../components/AiModelSettings";
import EnvironmentSettings from "../components/EnvironmentSettings";
import { API_BASE_URL } from "../api";

const SKILL_PATH = "/moses-monitoring-skill.md";

/**
 * The skill file has a {{MOSES_BASE_URL}} placeholder; fill in this instance's gateway so the
 * downloaded or copied text points at the right place.
 */
async function loadSkillText(): Promise<string> {
  const res = await fetch(SKILL_PATH);
  if (!res.ok) throw new Error(String(res.status));
  return (await res.text()).split("{{MOSES_BASE_URL}}").join(API_BASE_URL.replace(/\/+$/, ""));
}

/** Falls back to the plain link if the fetch fails. */
async function downloadSkill(event: React.MouseEvent<HTMLAnchorElement>) {
  event.preventDefault();
  const href = event.currentTarget.href;
  try {
    const text = await loadSkillText();
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
  const [skillCopy, setSkillCopy] = useState<"idle" | "copied" | "failed">("idle");

  async function copySkill() {
    try {
      await navigator.clipboard.writeText(await loadSkillText());
      setSkillCopy("copied");
    } catch {
      setSkillCopy("failed");
    }
    window.setTimeout(() => setSkillCopy("idle"), 2000);
  }

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
        <div className="flex items-center gap-space-sm">
          <a
            className="inline-flex items-center gap-1.5 px-space-md py-space-sm btn btn-primary shadow-md"
            href={SKILL_PATH}
            download
            onClick={downloadSkill}
          >
            <Icon name="download" size={18} />
            {t.settings.downloadSkill}
          </a>
          <button
            className="w-10 h-10 flex items-center justify-center rounded-xl btn-secondary"
            onClick={() => void copySkill()}
            title={skillCopy === "copied" ? t.team.copied : skillCopy === "failed" ? t.settings.copySkillFailed : t.settings.copySkill}
            aria-label={t.settings.copySkill}
            type="button"
          >
            <Icon
              name={skillCopy === "copied" ? "check" : skillCopy === "failed" ? "error" : "content_copy"}
              size={18}
              className={skillCopy === "copied" ? "text-secondary" : skillCopy === "failed" ? "text-error" : ""}
            />
          </button>
        </div>
      </div>
    </>
  );
}
