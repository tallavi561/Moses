import type { ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";

const STALE_AFTER_MS = 3 * 60 * 1000;

export function timeAgo(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

function barColor(percent: number): string {
  if (percent >= 90) return "bg-primary-container";
  if (percent >= 70) return "bg-tertiary";
  return "bg-secondary";
}

/** CPU / memory as last reported by the service's heartbeat, dimmed once the report is stale. */
export default function NodeMetrics({ state, compact = false }: { state: ProcessState; compact?: boolean }) {
  const { t } = useLanguage();
  const hasCpu = state.cpuPercent !== null && state.cpuPercent !== undefined;
  const hasMem = state.memoryMb !== null && state.memoryMb !== undefined;
  const ago = timeAgo(state.lastSeen);
  const stale = !state.lastSeen || Date.now() - Date.parse(state.lastSeen) > STALE_AFTER_MS;

  if (!hasCpu && !hasMem) {
    return compact ? null : (
      <p className="font-body-sm text-body-sm text-on-surface-variant">
        {t.flow.metricsNone}
        {ago ? ` - ${t.flow.lastSeen.replace("{ago}", ago)}` : ""}
      </p>
    );
  }

  const cpu = hasCpu ? state.cpuPercent! : 0;
  return (
    <div className={`flex flex-col gap-1 mt-space-xs ${stale ? "opacity-50" : ""}`}>
      {hasCpu && (
        <div className="flex items-center gap-space-xs">
          <span className="font-code text-code-sm text-on-surface-variant w-14 shrink-0">{t.flow.metricCpu}</span>
          <div className="flex-1 h-1.5 rounded-full bg-surface-container-highest overflow-hidden">
            <div className={`h-full rounded-full ${barColor(cpu)}`} style={{ width: `${Math.min(100, cpu)}%` }} />
          </div>
          <span className="font-code text-code-sm text-on-surface w-12 text-right shrink-0">{cpu.toFixed(0)}%</span>
        </div>
      )}
      {hasMem && (
        <div className="flex items-center gap-space-xs">
          <span className="font-code text-code-sm text-on-surface-variant w-14 shrink-0">{t.flow.metricMemory}</span>
          <span className="flex-1 font-code text-code-sm text-on-surface">{state.memoryMb!.toFixed(0)} MB</span>
        </div>
      )}
      {!compact && ago && (
        <span className="font-code text-code-sm text-on-surface-variant">{t.flow.lastSeen.replace("{ago}", ago)}</span>
      )}
    </div>
  );
}
