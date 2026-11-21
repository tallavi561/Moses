import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  fetchConnections,
  fetchLlmOverview,
  fetchLogCoverage,
  fetchProcessState,
  type Connection,
  type LlmOverview,
  type ProcessState,
  type ServiceLogCoverage,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

type Level = "ok" | "partial" | "missing" | "unsupported";

const LEVEL_STYLE: Record<Level, { icon: string; color: string }> = {
  ok: { icon: "check_circle", color: "text-secondary" },
  partial: { icon: "warning", color: "text-tertiary" },
  missing: { icon: "cancel", color: "text-error" },
  unsupported: { icon: "do_not_disturb_on", color: "text-on-surface-variant" },
};

interface Data {
  states: ProcessState[];
  connections: Connection[] | null;
  logs: ServiceLogCoverage[] | null;
  llm: LlmOverview | null;
}

function level(have: number, total: number): Level {
  if (total === 0 || have === 0) return "missing";
  return have >= total ? "ok" : "partial";
}

function fill(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce((s, [k, v]) => s.split(`{${k}}`).join(String(v)), template);
}

function Mark({ on, label }: { on: boolean; label?: string }) {
  return (
    <span className={`inline-flex items-center gap-1 ${on ? "text-secondary" : "text-on-surface-variant/60"}`}>
      <Icon name={on ? "check" : "close"} size={16} />
      {label}
    </span>
  );
}

/**
 * What MoSes can actually see in the current environment - per signal and per service - and what
 * it can't (yet). Built only from data that already arrived, so it reflects reality, not config.
 */
export default function CoveragePage() {
  const { t } = useLanguage();
  const { currentProject, currentEnvironment } = useProjects();
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const c = t.coverage;

  useEffect(() => {
    if (!currentProject) return;
    let cancelled = false;
    setData(null);
    Promise.allSettled([fetchProcessState(), fetchConnections(), fetchLogCoverage(24), fetchLlmOverview(currentProject.id)])
      .then(([states, connections, logs, llm]) => {
        if (cancelled) return;
        if (states.status === "rejected") {
          setError((states.reason as Error).message);
          return;
        }
        setData({
          states: states.value,
          connections: connections.status === "fulfilled" ? connections.value : null,
          logs: logs.status === "fulfilled" ? logs.value.services : null,
          llm: llm.status === "fulfilled" ? llm.value : null,
        });
      });
    return () => {
      cancelled = true;
    };
  }, [currentProject, currentEnvironment]);

  if (error) {
    return (
      <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
        <Icon name="error" size={18} />
        {error}
      </div>
    );
  }
  if (!data || !currentProject) {
    return (
      <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
        <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
        {c.loading}
      </div>
    );
  }

  const services = [...data.states].sort((a, b) => a.serviceName.localeCompare(b.serviceName));
  const total = services.length;
  const logsBy = new Map((data.logs ?? []).map((l) => [l.serviceName, l]));
  const connected = new Set((data.connections ?? []).flatMap((x) => [x.sourceService, x.targetService]));

  // DOWN usually means the heartbeats stopped, so only UP / DEGRADED count as "reporting now".
  const isReporting = (s: ProcessState) => s.status === "UP" || s.status === "DEGRADED";
  const reporting = services.filter(isReporting);
  const withLogs = services.filter((s) => (logsBy.get(s.serviceName)?.logCount ?? 0) > 0);
  const withMetrics = services.filter((s) => s.cpuPercent !== null || s.memoryMb !== null);
  const withTraces = services.filter((s) => (logsBy.get(s.serviceName)?.withTraceId ?? 0) > 0);
  const withProbe = services.filter((s) => s.probeUrl);
  const withConnections = services.filter((s) => connected.has(s.serviceName));
  const totalLogs = (data.logs ?? []).reduce((n, l) => n + l.logCount, 0);
  const tracedLogs = (data.logs ?? []).reduce((n, l) => n + l.withTraceId, 0);
  const llmSet = Boolean(
    data.llm && (data.llm.project || (currentEnvironment && data.llm.environments[currentEnvironment])),
  );

  const counts = (have: number) => fill(c.servicesCount, { have, total });
  const sources: { key: string; icon: string; title: string; level: Level; detail: string; fix?: string; link?: string }[] = [
    {
      key: "health",
      icon: "monitor_heart",
      title: c.healthTitle,
      level: level(reporting.length, total),
      detail: total === 0 ? c.noServices : fill(c.healthDetail, { have: reporting.length, total }),
      fix: c.healthFix,
    },
    {
      key: "logs",
      icon: "description",
      title: c.logsTitle,
      level: data.logs === null ? "missing" : level(withLogs.length, total),
      detail: data.logs === null ? c.unavailable : fill(c.logsDetail, { have: withLogs.length, total, logs: totalLogs }),
      fix: c.logsFix,
    },
    {
      key: "metrics",
      icon: "memory",
      title: c.metricsTitle,
      level: level(withMetrics.length, total),
      detail: counts(withMetrics.length),
      fix: c.metricsFix,
    },
    {
      key: "traces",
      icon: "timeline",
      title: c.tracesTitle,
      level: data.logs === null ? "missing" : level(withTraces.length, total),
      detail:
        totalLogs === 0
          ? c.tracesNoLogs
          : fill(c.tracesDetail, { pct: Math.round((tracedLogs / totalLogs) * 100), have: withTraces.length, total }),
      fix: c.tracesFix,
    },
    {
      key: "topology",
      icon: "account_tree",
      title: c.topologyTitle,
      level: data.connections === null ? "missing" : level(withConnections.length, total),
      detail: fill(c.topologyDetail, { connections: data.connections?.length ?? 0, have: withConnections.length, total }),
      fix: c.topologyFix,
    },
    {
      key: "probes",
      icon: "network_ping",
      title: c.probesTitle,
      level: withProbe.length > 0 ? "ok" : "partial",
      detail: counts(withProbe.length),
      fix: c.probesFix,
    },
    {
      key: "repo",
      icon: "code",
      title: c.repoTitle,
      level: currentProject.repoUrl ? "ok" : "missing",
      detail: currentProject.repoUrl ?? c.repoMissing,
      fix: c.repoFix,
      link: "settings",
    },
    {
      key: "ai",
      icon: "smart_toy",
      title: c.aiTitle,
      level: llmSet ? "ok" : "missing",
      detail: llmSet ? c.aiSet : c.aiMissing,
      fix: c.aiFix,
      link: "settings",
    },
  ];

  return (
    <div className="flex flex-col gap-space-md">
      <div>
        <h1 className="font-headline text-headline-lg text-on-surface">{t.nav.coverage}</h1>
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-1">
          {fill(c.pageHint, { env: currentEnvironment ?? "" })}
        </p>
      </div>

      <div className="grid gap-space-sm sm:grid-cols-2 xl:grid-cols-4">
        {sources.map((s) => {
          const style = LEVEL_STYLE[s.level];
          return (
            <div key={s.key} className="card flex flex-col gap-1.5">
              <div className="flex items-center gap-space-xs">
                <Icon name={s.icon} size={18} className="text-on-surface-variant" />
                <h2 className="font-headline text-headline-sm text-on-surface flex-1 min-w-0 truncate">{s.title}</h2>
                <span className={`flex items-center gap-1 font-label-sm text-label-sm ${style.color}`} title={c.levels[s.level]}>
                  <Icon name={style.icon} size={18} />
                  {c.levels[s.level]}
                </span>
              </div>
              <p className="font-body-sm text-body-sm text-on-surface break-words">{s.detail}</p>
              {s.level !== "ok" && s.fix && (
                <p className="font-body-sm text-body-sm text-on-surface-variant">
                  {s.fix}
                  {s.link && (
                    <>
                      {" "}
                      <Link to={`../${s.link}`} relative="path" className="text-secondary hover:underline">
                        {t.nav.settings}
                      </Link>
                    </>
                  )}
                </p>
              )}
            </div>
          );
        })}
      </div>

      <div className="card flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-md text-on-surface">{c.perServiceTitle}</h2>
        {total === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{c.noServices}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full font-body-sm text-body-sm">
              <thead>
                <tr className="text-on-surface-variant text-left">
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.colService}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.healthTitle}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.colLogs}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.metricsTitle}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.tracesTitle}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.topologyTitle}</th>
                  <th className="py-1.5 pe-space-md font-label-md text-label-md">{c.probesTitle}</th>
                </tr>
              </thead>
              <tbody>
                {services.map((s) => {
                  const l = logsBy.get(s.serviceName);
                  return (
                    <tr key={s.serviceName} className="border-t border-outline-variant/30">
                      <td className="py-1.5 pe-space-md font-code text-code-sm text-on-surface">{s.serviceName}</td>
                      <td className="py-1.5 pe-space-md"><Mark on={isReporting(s)} label={s.status !== "UNKNOWN" ? s.status : undefined} /></td>
                      <td className="py-1.5 pe-space-md"><Mark on={(l?.logCount ?? 0) > 0} label={l ? String(l.logCount) : undefined} /></td>
                      <td className="py-1.5 pe-space-md"><Mark on={s.cpuPercent !== null || s.memoryMb !== null} /></td>
                      <td className="py-1.5 pe-space-md"><Mark on={(l?.withTraceId ?? 0) > 0} /></td>
                      <td className="py-1.5 pe-space-md"><Mark on={connected.has(s.serviceName)} /></td>
                      <td className="py-1.5 pe-space-md"><Mark on={Boolean(s.probeUrl)} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="mt-space-xs font-body-sm text-body-sm text-on-surface-variant">{c.windowNote}</p>
          </div>
        )}
      </div>

      <div className="card flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-md text-on-surface">{c.notYetTitle}</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant">{c.notYetHint}</p>
        <ul className="flex flex-col gap-1.5">
          {c.notYet.map((item) => (
            <li key={item.title} className="flex items-start gap-space-xs font-body-sm text-body-sm">
              <Icon name="do_not_disturb_on" size={16} className="mt-0.5 shrink-0 text-on-surface-variant" />
              <span>
                <span className="text-on-surface">{item.title}</span>
                <span className="text-on-surface-variant"> - {item.detail}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
