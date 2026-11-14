/**
 * The signed-in session as the browser keeps it: the login token plus the user it belongs to.
 * Lives outside React so api.ts can attach the token to every request, and so a 401 from any
 * request can end the session no matter which screen made it.
 */

const STORAGE_KEY = "moses.session";

export interface SessionUser {
  id: string;
  name: string;
  email: string;
}

export interface Session {
  token: string;
  user: SessionUser;
}

type Listener = (session: Session | null) => void;

const listeners = new Set<Listener>();
let current: Session | null = readStored();

function readStored(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (typeof parsed?.token === "string" && typeof parsed?.user?.id === "string") return parsed;
  } catch {
    // unreadable storage or stale shape: treat as signed out
  }
  return null;
}

export function getSession(): Session | null {
  return current;
}

export function setSession(session: Session | null) {
  current = session;
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // storage blocked: the session still works until the tab is closed
  }
  listeners.forEach((listener) => listener(session));
}

export function subscribeSession(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
