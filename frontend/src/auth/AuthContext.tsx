import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { fetchMe, login, register } from "../api";
import { getSession, setSession, subscribeSession, type Session, type SessionUser } from "./session";

/**
 * Real accounts: email + password against auth-service, which answers with a signed token that
 * api.ts then sends on every request. Everything else in the app reads the user through
 * useAuth(), so adding Google/GitHub sign-in later only means another way to obtain a session.
 */

export interface AuthUser extends SessionUser {
  initials: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (name: string, email: string, password: string) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const letters = parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? "?").slice(0, 2);
  return letters.toUpperCase();
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(getSession);

  // Follows sign-in, sign-out, and a session ended by a 401 inside api.ts.
  useEffect(() => subscribeSession(setSessionState), []);

  // A stored session may be stale (expired token, renamed user): confirm it once on load.
  // An invalid token answers 401, which api.ts turns into a sign-out.
  useEffect(() => {
    const stored = getSession();
    if (!stored) return;
    fetchMe()
      .then((user) => {
        const latest = getSession();
        if (latest && latest.token === stored.token) setSession({ token: latest.token, user });
      })
      .catch(() => {
        // offline or the backend is still waking up: keep the stored session
      });
  }, []);

  async function signIn(email: string, password: string) {
    setSession(await login(email, password));
  }

  async function signUp(name: string, email: string, password: string) {
    setSession(await register(name, email, password));
  }

  function signOut() {
    setSession(null);
  }

  const user = session ? { ...session.user, initials: initialsOf(session.user.name) } : null;

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, signIn, signUp, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
