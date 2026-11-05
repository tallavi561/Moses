import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useOnClickOutside } from "./useOnClickOutside";
import { Icon } from "../icons";

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
    <div className="relative" ref={ref}>
      <button
        className="flex items-center gap-space-sm pl-space-xs"
        onClick={() => setOpen((v) => !v)}
        title={user.name}
        type="button"
      >
        <div className="hidden lg:flex flex-col text-right">
          <span className="font-headline text-headline-sm text-on-surface text-[14px] leading-tight">
            {user.name}
          </span>
          <span className="font-label-sm text-label-sm text-primary">{user.email}</span>
        </div>
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary font-label-md text-label-md shrink-0">
          {user.initials}
        </div>
      </button>

      {open && (
        <div className="absolute right-0 top-[calc(100%+8px)] w-56 bg-surface-container-low rounded-2xl shadow-2xl p-space-sm z-50 space-y-1">
          <div className="px-space-sm py-space-xs">
            <div className="font-headline text-headline-sm text-on-surface">{user.name}</div>
            <div className="font-body-sm text-body-sm text-on-surface-variant">{user.email}</div>
          </div>
          <div className="h-px bg-outline-variant my-1" />
          <button
            className="w-full text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high text-on-surface font-body-md text-body-md transition-colors flex items-center gap-1.5"
            onClick={handleSignOut}
            type="button"
          >
            <Icon name="logout" size={16} />
            {t.auth.signOut}
          </button>
        </div>
      )}
    </div>
  );
}
