import { useEffect, useState } from "react";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/** Chrome/Edge/Samsung Internet's install event (not in the TS DOM types). */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

const DISMISSED_KEY = "moses.install.dismissedAt";
const SNOOZE_MS = 14 * 24 * 60 * 60 * 1000;

function isStandalone(): boolean {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

function isPhone(): boolean {
  return /Android|iPhone|iPod/i.test(navigator.userAgent) || (window.matchMedia("(pointer: coarse)").matches && window.innerWidth < 768);
}

function isIos(): boolean {
  return /iPhone|iPad|iPod/i.test(navigator.userAgent);
}

function snoozed(): boolean {
  try {
    const at = Number(localStorage.getItem(DISMISSED_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < SNOOZE_MS;
  } catch {
    return false;
  }
}

function snooze() {
  try {
    localStorage.setItem(DISMISSED_KEY, String(Date.now()));
  } catch {
    // private mode etc.: the banner just comes back next visit
  }
}

/**
 * Offers to install MoSes on a phone's home screen. Android (Chrome and friends): an Install
 * button that opens the browser's own install dialog. iPhone: Safari has no such dialog, so the
 * banner explains Share > Add to Home Screen. Never shown once installed, on a desktop, or for
 * two weeks after "Not now". Installing needs the site on HTTPS (or localhost).
 */
export default function InstallPrompt() {
  const { t } = useLanguage();
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [visible, setVisible] = useState(false);
  const ios = isIos();

  useEffect(() => {
    if (isStandalone() || !isPhone() || snoozed()) return;
    if (ios) {
      setVisible(true);
      return;
    }
    const onPrompt = (e: Event) => {
      e.preventDefault(); // show our banner instead of the browser's mini-infobar
      setDeferred(e as BeforeInstallPromptEvent);
      setVisible(true);
    };
    const onInstalled = () => setVisible(false);
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onPrompt);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, [ios]);

  if (!visible) return null;

  async function install() {
    if (!deferred) return;
    await deferred.prompt();
    const choice = await deferred.userChoice;
    setDeferred(null);
    setVisible(false);
    if (choice.outcome === "dismissed") snooze();
  }

  function later() {
    snooze();
    setVisible(false);
  }

  const [before, after] = t.install.iosSteps.split("{share}");
  return (
    <div
      role="dialog"
      aria-label={t.install.title}
      className="fixed inset-x-3 bottom-3 z-50 flex items-start gap-space-sm rounded-2xl bg-surface-container-high border border-outline-variant/40 shadow-xl p-space-sm"
      style={{ paddingBottom: "max(0.5rem, env(safe-area-inset-bottom))" }}
    >
      <img src="/icon-192.png" alt="" className="w-12 h-12 rounded-xl shrink-0" />
      <div className="flex-1 min-w-0">
        <div className="font-label-md text-label-md text-on-surface">{t.install.title}</div>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-0.5">
          {ios ? (
            <>
              {before}
              <Icon name="ios_share" size={16} className="align-middle text-secondary" />
              {after}
            </>
          ) : (
            t.install.body
          )}
        </p>
        <div className="flex items-center gap-space-sm mt-space-xs">
          {!ios && (
            <button type="button" className="px-space-md py-1 btn btn-primary" onClick={() => void install()}>
              {t.install.button}
            </button>
          )}
          <button
            type="button"
            className="px-space-sm py-1 rounded-lg font-label-md text-label-md text-on-surface-variant hover:text-on-surface"
            onClick={later}
          >
            {t.install.later}
          </button>
        </div>
      </div>
    </div>
  );
}
