import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useOnClickOutside } from "./useOnClickOutside";

export default function UserMenu() {
  const { user, signOut } = useAuth();
  const { t } = useLanguage();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useOnClickOutside(ref, () => setOpen(false));

  if (!user) return null;

  function handleSignOut() {
    signOut();
    setOpen(false);
    navigate("/login");
  }

  return (
    <div className="user-menu" ref={ref}>
      <button className="user-menu-trigger" onClick={() => setOpen((v) => !v)} title={user.name}>
        <span className="user-avatar">{user.initials}</span>
      </button>

      {open && (
        <div className="user-menu-dropdown">
          <div className="user-menu-info">
            <div className="brand-name">{user.name}</div>
            <div className="brand-tagline">{user.email}</div>
          </div>
          <div className="project-switcher-divider" />
          <button className="project-switcher-item" onClick={handleSignOut}>
            {t.auth.signOut}
          </button>
        </div>
      )}
    </div>
  );
}
