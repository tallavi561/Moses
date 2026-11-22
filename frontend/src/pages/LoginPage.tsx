import { useCallback, useState, type FormEvent, type ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { ApiError } from "../api";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useTheme } from "../theme/ThemeContext";
import { Icon } from "../icons";
import HeroLogo from "../components/HeroLogo";
import NameMorph from "../components/NameMorph";
import TypedHeadline from "../components/TypedHeadline";

const MIN_PASSWORD_LENGTH = 8;

/**
 * Sign in / create account. Two halves on a wide screen: the brand panel (always dark, like the
 * stage behind the logo) says what MoSes is; the form sits on the app's own surface. On a phone
 * the panel shrinks to a band with the eagle, the name and the headline above the form.
 *
 * The brand panel plays in order: the name unfolds into Monitoring Services (NameMorph), and only
 * then does the headline start typing.
 */
export default function LoginPage() {
  const { isAuthenticated, signIn, signUp } = useAuth();
  const { t, lang, toggleLang } = useLanguage();
  const { mode: theme, toggleMode } = useTheme();
  const location = useLocation();
  const [mode, setMode] = useState<"signIn" | "signUp">("signIn");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const a = t.auth;

  if (isAuthenticated) {
    // Back to where the visitor was headed before being asked to sign in (e.g. an invite link).
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from ?? "/projects"} replace />;
  }

  const signingUp = mode === "signUp";

  function explain(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.status === 401) return a.wrongCredentials;
      if (e.status === 409) return a.emailTaken;
      if (e.status === 400) return a.invalidDetails;
    }
    return a.unavailable;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (signingUp && password.length < MIN_PASSWORD_LENGTH) {
      setError(a.passwordTooShort);
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

  const control =
    "flex items-center gap-1.5 h-9 px-space-sm rounded-lg text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors font-label-md text-label-md";

  return (
    <div className="min-h-screen w-full bg-background lg:grid lg:grid-cols-[1.1fr_1fr]">
      <BrandPanel />

      <section className="relative flex flex-col min-h-[60vh] lg:min-h-screen">
        <div className="flex items-center justify-end gap-1 p-space-md">
          <button type="button" className={control} onClick={toggleLang} title="Language">
            <Icon name="language" size={18} />
            {lang === "en" ? "עברית" : "English"}
          </button>
          <button
            type="button"
            className={control}
            onClick={toggleMode}
            title={theme === "dark" ? a.lightMode : a.darkMode}
            aria-label={theme === "dark" ? a.lightMode : a.darkMode}
          >
            <Icon name={theme === "dark" ? "light_mode" : "dark_mode"} size={18} />
          </button>
        </div>

        <div className="flex-1 flex items-center justify-center px-space-md pb-space-xl">
          <form
            dir={lang === "he" ? "rtl" : "ltr"}
            className="w-full max-w-[380px] flex flex-col gap-space-lg"
            onSubmit={handleSubmit}
          >
            <div className="flex flex-col gap-space-xs">
              <img src="/icon-192.png" alt="" className="w-11 h-11 rounded-xl mb-space-sm" />
              <h1 className="font-headline text-[30px] leading-[36px] font-semibold tracking-[-0.02em] text-on-surface">
                {signingUp ? a.signUpTitle : a.signInTitle}
              </h1>
              <p className="font-body-md text-body-md text-on-surface-variant">{signingUp ? a.signUpLead : a.signInLead}</p>
            </div>

            <div className="flex flex-col gap-space-md">
              {signingUp && (
                <Field label={a.name} icon="person">
                  <input
                    className="login-input"
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    autoComplete="name"
                    maxLength={80}
                    required
                  />
                </Field>
              )}
              <Field label={a.email} icon="mail">
                <input
                  className="login-input"
                  type="email"
                  dir="ltr"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  placeholder="name@company.com"
                  required
                />
              </Field>
              <Field
                label={a.password}
                icon="lock"
                hint={signingUp ? a.passwordHint : undefined}
                trailing={
                  <button
                    type="button"
                    className="p-1 rounded-md text-on-surface-variant hover:text-on-surface"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? a.hidePassword : a.showPassword}
                    title={showPassword ? a.hidePassword : a.showPassword}
                  >
                    <Icon name={showPassword ? "visibility_off" : "visibility"} size={18} />
                  </button>
                }
              >
                <input
                  className="login-input"
                  type={showPassword ? "text" : "password"}
                  dir="ltr"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete={signingUp ? "new-password" : "current-password"}
                  maxLength={72}
                  required
                />
              </Field>
            </div>

            {error && (
              <div
                className="flex items-start gap-space-xs rounded-xl bg-error-container/20 border border-error/30 px-space-sm py-space-xs font-body-sm text-body-sm text-error"
                role="alert"
              >
                <Icon name="error" size={18} className="shrink-0 mt-px" />
                {error}
              </div>
            )}

            <button
              className="w-full h-12 flex items-center justify-center gap-space-sm btn btn-primary shadow-md disabled:opacity-60"
              disabled={busy}
              type="submit"
            >
              {busy ? <Icon name="progress_activity" size={20} className="animate-spin" /> : null}
              {busy ? a.working : signingUp ? a.signUp : a.signIn}
            </button>

            <div className="flex items-center justify-center gap-1.5 font-body-sm text-body-sm text-on-surface-variant">
              {signingUp ? a.haveAccountQ : a.noAccountQ}
              <button className="font-label-md text-label-md text-secondary hover:underline" onClick={switchMode} type="button">
                {signingUp ? a.signIn : a.createAccount}
              </button>
            </div>
          </form>
        </div>

        <div dir="ltr" className="px-space-md pb-space-md font-code text-[11px] text-on-surface-variant/50 text-center lg:text-left">
          MoSes Monitor v{__APP_VERSION__}
        </div>
      </section>
    </div>
  );
}

function Field({
  label,
  icon,
  hint,
  trailing,
  children,
}: {
  label: string;
  icon: string;
  hint?: string;
  trailing?: ReactNode;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="font-label-md text-label-md text-on-surface">{label}</span>
      <span className="login-field">
        <Icon name={icon} size={18} className="text-on-surface-variant shrink-0" />
        {children}
        {trailing}
      </span>
      {hint && <span className="font-body-sm text-body-sm text-on-surface-variant">{hint}</span>}
    </label>
  );
}

function BrandPanel() {
  const { t, lang } = useLanguage();
  const [named, setNamed] = useState(false);
  const finishName = useCallback(() => setNamed(true), []);
  const a = t.auth;
  const points: { icon: string; title: string; body: string }[] = [
    { icon: "e911_emergency", title: a.point1Title, body: a.point1 },
    { icon: "forum", title: a.point2Title, body: a.point2 },
    { icon: "history", title: a.point3Title, body: a.point3 },
  ];
  return (
    <section className="login-brand relative overflow-hidden flex flex-col px-space-lg pt-space-md pb-space-lg lg:px-14 lg:py-12 lg:min-h-screen">
      <div className="login-brand__grid" aria-hidden="true" />
      <div className="relative flex-1 flex flex-col items-center justify-center py-space-sm">
        <HeroLogo compact />
        <NameMorph onDone={finishName} />
      </div>

      <div dir={lang === "he" ? "rtl" : "ltr"} className="relative flex flex-col gap-space-lg max-w-[560px]">
        <TypedHeadline
          key={lang}
          play={named}
          lines={[a.typedWake, a.typedHover, a.brandTagline]}
          className="font-headline text-[22px] leading-[28px] lg:text-[34px] lg:leading-[40px] font-semibold tracking-[-0.02em] text-[#f1f0f5]"
        />
        <ul className="hidden lg:grid gap-space-md">
          {points.map((p) => (
            <li key={p.icon} className="flex items-start gap-space-sm">
              <span className="w-9 h-9 rounded-lg bg-[#4cd7f6]/10 text-[#4cd7f6] flex items-center justify-center shrink-0">
                <Icon name={p.icon} size={18} />
              </span>
              <span className="flex flex-col">
                <span className="font-label-md text-label-md text-[#e9e8ee]">{p.title}</span>
                <span className="font-body-sm text-body-sm text-[#8f8e99]">{p.body}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
