import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

export default function LoginPage() {
  const { isAuthenticated, signIn } = useAuth();
  const { t } = useLanguage();
  const navigate = useNavigate();

  if (isAuthenticated) {
    return <Navigate to="/projects" replace />;
  }

  function handleSignIn() {
    signIn();
    navigate("/projects");
  }

  return (
    <div className="min-h-screen w-full bg-background flex items-center justify-center p-space-md relative overflow-hidden">
      <div className="absolute -top-32 -left-32 w-96 h-96 rounded-full bg-secondary/5 blur-3xl pointer-events-none" />
      <div className="absolute top-1/2 right-0 w-96 h-96 rounded-full bg-primary-container/10 blur-3xl pointer-events-none" />
      <div
        className="absolute inset-0 pointer-events-none opacity-20"
        style={{
          backgroundImage: "radial-gradient(rgba(76,215,246,0.25) 1px, transparent 1px)",
          backgroundSize: "28px 28px",
        }}
      />

      <div className="relative w-full max-w-sm bg-surface-container-low rounded-2xl shadow-2xl p-space-xl flex flex-col items-center text-center gap-space-md">
        <div className="w-16 h-16 rounded-2xl bg-surface-container-high flex items-center justify-center relative">
          <Icon name="hub" size={32} className="text-primary" />
          <span className="absolute top-1.5 right-1.5 w-2.5 h-2.5 rounded-full bg-secondary" />
        </div>

        <div>
          <h1 className="font-headline text-headline-lg text-on-surface">{t.auth.welcome}</h1>
          <p className="font-body-md text-body-md text-on-surface-variant mt-space-xs">{t.auth.subtitle}</p>
        </div>

        <button
          className="w-full flex items-center justify-center gap-space-sm py-space-sm px-space-md rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all shadow-md"
          onClick={handleSignIn}
          type="button"
        >
          <Icon name="login" size={18} />
          {t.auth.signIn}
        </button>

        <p className="font-code text-code-sm text-on-surface-variant leading-relaxed">{t.auth.mockNotice}</p>
      </div>
    </div>
  );
}
