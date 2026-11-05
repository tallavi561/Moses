import type { Strings } from "../i18n/strings";

export interface NavItem {
  path: string;
  labelKey: keyof Strings["nav"];
  icon: string;
  enabled: boolean;
}

/**
 * Single source of truth for sidebar nav - add a screen here once it's
 * specified. Paths are relative to the current project (/p/:projectId/...).
 * `icon` is a Material Symbols Outlined icon name (see icons.tsx).
 */
export const NAV_ITEMS: NavItem[] = [
  { path: "search", labelKey: "search", icon: "terminal", enabled: true },
  { path: "flow", labelKey: "flow", icon: "account_tree", enabled: true },
  { path: "dashboard", labelKey: "dashboard", icon: "monitoring", enabled: false },
  { path: "uploads", labelKey: "uploads", icon: "multiline_chart", enabled: false },
  { path: "settings", labelKey: "settings", icon: "tune", enabled: true },
];
