import type { Lang } from "./i18n/strings";

/** "45s", "12 min", "3h 5min" - or the Hebrew equivalent. */
export function formatDuration(seconds: number, lang: Lang): string {
  const s = Math.max(0, Math.round(seconds));
  const he = lang === "he";
  if (s < 120) return he ? `${s} שניות` : `${s}s`;
  const minutes = Math.floor(s / 60);
  if (minutes < 120) return he ? `${minutes} דק'` : `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours < 48) return he ? `${hours} שע' ${rest} דק'` : `${hours}h ${rest}min`;
  const days = Math.floor(hours / 24);
  return he ? `${days} ימים ${hours % 24} שע'` : `${days}d ${hours % 24}h`;
}

export function fillTemplate(template: string, values: Record<string, string | number>): string {
  return Object.entries(values).reduce((s, [k, v]) => s.split(`{${k}}`).join(String(v)), template);
}
