import { useState, type FormEvent } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { ApiError } from "../api";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import HeroLogo from "../components/HeroLogo";

const MIN_PASSWORD_LENGTH = 8;

const INPUT_CLASS =
  "w-full bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant";

export default function LoginPage() {
  const { isAuthenticated, signIn, signUp } = useAuth();
  const { t } = useLanguage();
  const location = useLocation();
  const [mode, setMode] = useState<"signIn" | "signUp">("signIn");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (isAuthenticated) {
    // Back to where the visitor was headed before being asked to sign in (e.g. an invite link).
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from ?? "/projects"} replace />;
  }

  const signingUp = mode === "signUp";

  function explain(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.status === 401) return t.auth.wrongCredentials;
      if (e.status === 409) return t.auth.emailTaken;
      if (e.status === 400) return t.auth.invalidDetails;
    }
    return t.auth.unavailable;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (signingUp && password.length < MIN_PASSWORD_LENGTH) {
      setError(t.auth.passwordTooShort);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (signingUp) await signUp(name.trim(), email.trim(), password);
      else await signIn(email.trim(), password);
    } catch (e) {
      setError(explain(e));
      setBusy(false);
    }
  }

  function switchMode() {
    setMode(signingUp ? "signIn" : "signUp");
    setError(null);
  }

  return (
    <div className="min-h-screen w-full bg-background flex items-center justify-center p-space-md relative overflow-hidden">
      <div className="absolute -top-32 -left-32 w-96 h-96 rounded-full bg-secondary/5 blur-3xl pointer-events-none" />
      <div className="absolute top-1/2 right-0 w-96 h-96 rounded-full bg-primary-container/10 blur-3xl pointer-events-none" />
      <div
        className="absolute inset-0 pointer-events-none opacity-20"
        style={{
          backgroundImage: "radial-gradient(var(--dot-grid) 1px, transparent 1px)",
          backgroundSize: "28px 28px",
        }}
      />

      <div className="relative w-full flex flex-col items-center gap-space-md py-space-md">
        <HeroLogo />

        <form
          className="w-full max-w-sm bg-surface-container-low rounded-2xl shadow-2xl p-space-xl flex flex-col gap-space-md"
          onSubmit={handleSubmit}
        >
          <div className="text-center">
            <h1 className="font-headline text-headline-lg text-on-surface">{t.auth.welcome}</h1>
            <p className="font-body-md text-body-md text-on-surface-variant mt-space-xs">
              {signingUp ? t.auth.subtitleSignUp : t.auth.subtitle}
            </p>
          </div>

          <div className="flex flex-col gap-space-sm">
            {signingUp && (
              <input
                className={INPUT_CLASS}
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t.auth.name}
                aria-label={t.auth.name}
                autoComplete="name"
                maxLength={80}
                required
              />
            )}
            <input
              className={INPUT_CLASS}
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={t.auth.email}
              aria-label={t.auth.email}
              autoComplete="email"
              required
            />
            <input
              className={INPUT_CLASS}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={signingUp ? t.auth.passwordNew : t.auth.password}
              aria-label={t.auth.password}
              autoComplete={signingUp ? "new-password" : "current-password"}
              maxLength={72}
              required
            />
          </div>

          {error && (
            <p className="font-body-sm text-body-sm text-error" role="alert">
              {error}
            </p>
          )}

          <button
            className="w-full flex items-center justify-center gap-space-sm py-space-sm px-space-md btn btn-primary shadow-md disabled:opacity-50"
            disabled={busy}
            type="submit"
          >
            <Icon name={signingUp ? "person_add" : "login"} size={18} />
            {busy ? t.auth.working : signingUp ? t.auth.signUp : t.auth.signIn}
          </button>

          <button
            className="font-body-sm text-body-sm text-secondary hover:underline self-center"
            onClick={switchMode}
            type="button"
          >
            {signingUp ? t.auth.haveAccount : t.auth.noAccount}
          </button>
        </form>
      </div>
    </div>
  );
}
