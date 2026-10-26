import { IconDashboard, IconFlow, IconSearch, IconSettings, IconUploads } from "../icons";
import type { Strings } from "../i18n/strings";

export interface NavItem {
  path: string;
  labelKey: keyof Strings["nav"];
  icon: typeof IconSearch;
  enabled: boolean;
}

/**
 * Single source of truth for sidebar nav - add a screen here once it's
 * specified. Paths are relative to the current project (/p/:projectId/...).
 */
export const NAV_ITEMS: NavItem[] = [
  { path: "search", labelKey: "search", icon: IconSearch, enabled: true },
  { path: "flow", labelKey: "flow", icon: IconFlow, enabled: true },
  { path: "dashboard", labelKey: "dashboard", icon: IconDashboard, enabled: false },
  { path: "uploads", labelKey: "uploads", icon: IconUploads, enabled: false },
  { path: "settings", labelKey: "settings", icon: IconSettings, enabled: true },
];
