import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";

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
    <div className="auth-screen">
      <div className="card auth-card">
        <div className="logo auth-logo">MS</div>
        <h2>{t.auth.welcome}</h2>
        <p className="hint">{t.auth.subtitle}</p>
        <button className="btn" onClick={handleSignIn}>
          {t.auth.signIn}
        </button>
        <p className="mock-notice">{t.auth.mockNotice}</p>
      </div>
    </div>
  );
}
