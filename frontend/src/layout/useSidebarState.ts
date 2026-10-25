import { useState } from "react";

export type SidebarState = "expanded" | "collapsed" | "hidden";

const STORAGE_KEY = "moses.sidebar";

function readStored(): SidebarState {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "expanded" || stored === "collapsed" || stored === "hidden") return stored;
  } catch {
    // localStorage unavailable - fall through to default
  }
  return "expanded";
}

function persist(state: SidebarState) {
  try {
    localStorage.setItem(STORAGE_KEY, state);
  } catch {
    // ignore - per-viewer convenience only
  }
}

export function useSidebarState() {
  const [state, setState] = useState<SidebarState>(readStored);

  function set(next: SidebarState) {
    setState(next);
    persist(next);
  }

  function collapseToggle() {
    set(state === "expanded" ? "collapsed" : "expanded");
  }

  function hide() {
    set("hidden");
  }

  function show() {
    set("expanded");
  }

  return { state, collapseToggle, hide, show };
}
