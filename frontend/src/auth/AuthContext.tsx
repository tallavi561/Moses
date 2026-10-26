import { createContext, useContext, useState, type ReactNode } from "react";

/**
 * MOCK auth. There is no real backend authentication yet (deferred on
 * purpose - see chat). This exists only so the UI flow (login -> projects ->
 * workspace) can be built and tested now; swap this out wholesale once real
 * auth lands, nothing else in the app should need to change since everything
 * reads through useAuth().
 */

const STORAGE_KEY = "moses.auth";

export interface MockUser {
  name: string;
  email: string;
  initials: string;
}

const DEMO_USER: MockUser = {
  name: "Demo User",
  email: "demo@moses.local",
  initials: "DU",
};

interface AuthContextValue {
  user: MockUser | null;
  isAuthenticated: boolean;
  signIn: () => void;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function readStored(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "signed-in";
  } catch {
    return false;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState<boolean>(readStored);

  function signIn() {
    setSignedIn(true);
    try {
      localStorage.setItem(STORAGE_KEY, "signed-in");
    } catch {
      // ignore - per-viewer convenience only
    }
  }

  function signOut() {
    setSignedIn(false);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
  }

  return (
    <AuthContext.Provider
      value={{ user: signedIn ? DEMO_USER : null, isAuthenticated: signedIn, signIn, signOut }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
