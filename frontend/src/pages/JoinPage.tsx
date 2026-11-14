import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { acceptInvite, fetchInvitePreview, type InvitePreview } from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import HeroLogo from "../components/HeroLogo";

/** Landing page of an invite link (/join/:token). Reached only when signed in - see App.tsx. */
export default function JoinPage() {
  const { token = "" } = useParams<{ token: string }>();
  const { t } = useLanguage();
  const { reload, selectProject } = useProjects();
  const navigate = useNavigate();
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "invalid" | "joining" | "failed">("loading");

  useEffect(() => {
    let cancelled = false;
    fetchInvitePreview(token)
      .then((p) => {
        if (cancelled) return;
        setPreview(p);
        setState("ready");
      })
      .catch(() => {
        if (!cancelled) setState("invalid");
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  async function handleJoin() {
    setState("joining");
    try {
      const project = await acceptInvite(token);
      await reload();
      selectProject(project.id);
      navigate(`/p/${project.id}/search`, { replace: true });
    } catch {
      setState("failed");
    }
  }

  return (
    <div className="min-h-screen w-full bg-background flex items-center justify-center p-space-md">
      <div className="w-full flex flex-col items-center gap-space-md">
        <HeroLogo compact />
        <div className="w-full max-w-sm bg-surface-container-low rounded-2xl shadow-2xl p-space-xl flex flex-col items-center text-center gap-space-md">
          {state === "loading" && (
            <p className="font-body-md text-body-md text-on-surface-variant">{t.join.loading}</p>
          )}

          {state === "invalid" && (
            <>
              <Icon name="link_off" size={32} className="text-on-surface-variant" />
              <p className="font-body-md text-body-md text-on-surface">{t.join.invalid}</p>
            </>
          )}

          {preview && state !== "loading" && state !== "invalid" && (
            <>
              <Icon name="group_add" size={32} className="text-secondary" />
              <div>
                <p className="font-body-md text-body-md text-on-surface-variant">{t.join.invitedTo}</p>
                <h1 className="font-headline text-headline-lg text-on-surface break-words">{preview.projectName}</h1>
              </div>
              {state === "failed" && (
                <p className="font-body-sm text-body-sm text-error" role="alert">
                  {t.join.failed}
                </p>
              )}
              <button
                className="w-full flex items-center justify-center gap-space-sm py-space-sm px-space-md rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all shadow-md disabled:opacity-50"
                disabled={state === "joining"}
                onClick={handleJoin}
                type="button"
              >
                {state === "joining" ? t.join.joining : t.join.join}
              </button>
            </>
          )}

          <Link className="font-body-sm text-body-sm text-secondary hover:underline" to="/projects">
            {t.join.backToProjects}
          </Link>
        </div>
      </div>
    </div>
  );
}
