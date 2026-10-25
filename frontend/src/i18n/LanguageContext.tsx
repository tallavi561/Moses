import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { strings, type Lang, type Strings } from "./strings";

const STORAGE_KEY = "moses.lang";
const RTL_LANGS: Lang[] = ["he"];

interface LanguageContextValue {
  lang: Lang;
  t: Strings;
  setLang: (lang: Lang) => void;
  toggleLang: () => void;
  dir: "rtl" | "ltr";
}

const LanguageContext = createContext<LanguageContextValue | null>(null);

function readStoredLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "en" || stored === "he") return stored;
  } catch {
    // localStorage unavailable (private mode etc.) - fall through to default
  }
  return "en";
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(readStoredLang);
  const dir = RTL_LANGS.includes(lang) ? "rtl" : "ltr";

  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = dir;
  }, [lang, dir]);

  function setLang(next: Lang) {
    setLangState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // ignore - per-viewer convenience only
    }
  }

  function toggleLang() {
    setLang(lang === "en" ? "he" : "en");
  }

  return (
    <LanguageContext.Provider value={{ lang, t: strings[lang], setLang, toggleLang, dir }}>
      {children}
    </LanguageContext.Provider>
  );
}

export function useLanguage() {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error("useLanguage must be used within LanguageProvider");
  return ctx;
}

/** Simple pluralization + {count} interpolation for the two-form (one/other) strings above. */
export function pluralize(one: string, other: string, count: number): string {
  const template = count === 1 ? one : other;
  return template.replace("{count}", String(count));
}
