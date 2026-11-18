import { useEffect, useState } from "react";
import {
  createDashboard,
  deleteDashboard,
  fetchDashboards,
  fetchProcessState,
  type DashboardScope,
  type DashboardView,
  type ProcessState,
} from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { STATUS_BADGE, STATUS_DOT } from "../statusStyles";
import MetricsChart from "../components/MetricsChart";

function NewDashboardForm({
  services,
  onCreated,
  onCancel,
}: {
  services: string[];
  onCreated: (d: DashboardView) => void;
  onCancel: () => void;
}) {
  const { t } = useLanguage();
  const [scope, setScope] = useState<DashboardScope>("ENVIRONMENT");
  const [serviceName, setServiceName] = useState(services[0] ?? "");
  const [title, setTitle] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (scope === "SERVICE" && !serviceName) return;
    setSaving(true);
    setError(null);
    try {
      const created = await createDashboard({
        scope,
        serviceName: scope === "SERVICE" ? serviceName : undefined,
        title: title.trim() || undefined,
      });
      onCreated(created);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card flex flex-col gap-space-sm">
      <h3 className="font-headline text-headline-sm text-on-surface">{t.dashboard.newTitle}</h3>
      <div className="flex items-center bg-surface-container-high rounded-lg p-0.5 w-fit" role="radiogroup">
        {(["ENVIRONMENT", "SERVICE"] as const).map((s) => (
          <button
            key={s}
            type="button"
            className={`px-space-sm py-1 rounded-md font-code text-code-sm transition-all ${
              scope === s ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
            }`}
            aria-pressed={scope === s}
            onClick={() => setScope(s)}
          >
            {s === "ENVIRONMENT" ? t.dashboard.scopeEnvironment : t.dashboard.scopeService}
          </button>
        ))}
      </div>

      {scope === "SERVICE" && (
        <select
          value={serviceName}
          onChange={(e) => setServiceName(e.target.value)}
          className="field max-w-xs"
          aria-label={t.dashboard.scopeService}
        >
          {services.length === 0 && <option value="">{t.dashboard.noServices}</option>}
          {services.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      )}

      <input
        type="text"
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder={t.dashboard.titlePlaceholder}
        className="field max-w-xs"
      />

      {error && (
        <p className="flex items-center gap-1 font-body-sm text-body-sm text-error">
          <Icon name="error" size={15} />
          {error}
        </p>
      )}

      <div className="flex items-center gap-space-sm">
        <button
          type="button"
          className="flex items-center gap-1.5 px-space-md py-space-sm btn btn-primary disabled:opacity-40"
          onClick={submit}
          disabled={saving || (scope === "SERVICE" && !serviceName)}
        >
          <Icon name="add" size={18} />
          {t.dashboard.create}
        </button>
        <button type="button" className="px-space-md py-space-sm btn btn-secondary" onClick={onCancel}>
          {t.dashboard.cancelCreate}
        </button>
      </div>
    </div>
  );
}

function DashboardCard({
  dashboard,
  allServices,
  state,
  onDelete,
}: {
  dashboard: DashboardView;
  allServices: string[];
  state: ProcessState | undefined;
  onDelete: (id: string) => void;
}) {
  const { t } = useLanguage();
  const [confirming, setConfirming] = useState(false);
  const initialSelected = dashboard.scope === "SERVICE" && dashboard.serviceName ? [dashboard.serviceName] : allServices;

  return (
    <div className="card flex flex-col gap-space-sm">
      <div className="flex items-start justify-between gap-space-sm">
        <div className="flex items-center gap-space-xs min-w-0">
          {dashboard.scope === "SERVICE" && state && (
            <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${STATUS_DOT[state.status] ?? STATUS_DOT.UNKNOWN}`} />
          )}
          <h2 className="font-headline text-headline-md text-on-surface truncate">{dashboard.title}</h2>
          <span className="shrink-0 font-code text-code-sm text-on-surface-variant bg-surface-container px-space-sm py-0.5 rounded-full">
            {dashboard.scope === "SERVICE" ? t.dashboard.scopeService : t.dashboard.scopeEnvironment}
          </span>
          {dashboard.createdBy === "agent" && (
            <span
              className="shrink-0 flex items-center gap-1 font-code text-code-sm text-secondary bg-secondary-container/20 px-space-sm py-0.5 rounded-full"
              title={t.dashboard.createdByAgentHint}
            >
              <Icon name="smart_toy" size={13} />
              {t.dashboard.createdByAgent}
            </span>
          )}
          {dashboard.scope === "SERVICE" && state && (
            <span className={`shrink-0 font-code text-code-sm px-1.5 py-0.5 rounded font-semibold ${STATUS_BADGE[state.status] ?? STATUS_BADGE.UNKNOWN}`}>
              {t.flow.status[state.status]}
            </span>
          )}
        </div>
        {confirming ? (
          <div className="shrink-0 flex items-center gap-1.5">
            <span className="font-body-sm text-body-sm text-on-surface-variant">{t.dashboard.deleteConfirm}</span>
            <button
              type="button"
              className="px-space-sm py-1 rounded-lg bg-error-container/20 text-error font-label-md text-label-md"
              onClick={() => onDelete(dashboard.id)}
            >
              {t.dashboard.delete}
            </button>
            <button
              type="button"
              className="px-space-sm py-1 rounded-lg bg-surface-container-high text-on-surface font-label-md text-label-md"
              onClick={() => setConfirming(false)}
            >
              {t.dashboard.cancelCreate}
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="shrink-0 p-1.5 rounded-lg text-on-surface-variant hover:bg-surface-container-high hover:text-error transition-colors"
            onClick={() => setConfirming(true)}
            aria-label={t.dashboard.delete}
          >
            <Icon name="delete" size={18} />
          </button>
        )}
      </div>

      <MetricsChart availableServices={allServices} initialSelected={initialSelected} />
    </div>
  );
}

export default function DashboardPage() {
  const { t } = useLanguage();
  const [dashboards, setDashboards] = useState<DashboardView[] | null>(null);
  const [states, setStates] = useState<ProcessState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    Promise.all([fetchDashboards(), fetchProcessState()])
      .then(([d, s]) => {
        setDashboards(d);
        setStates(s);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const allServices = states.map((s) => s.serviceName).sort();

  function handleCreated(d: DashboardView) {
    setDashboards((prev) => (prev ? [...prev.filter((x) => x.id !== d.id), d] : [d]));
    setCreating(false);
  }

  async function handleDelete(id: string) {
    try {
      await deleteDashboard(id);
      setDashboards((prev) => (prev ? prev.filter((d) => d.id !== id) : prev));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="flex flex-col gap-space-md">
      <div className="flex items-center justify-between gap-space-sm">
        <div>
          <h1 className="font-headline text-headline-lg text-on-surface">{t.nav.dashboard}</h1>
          <p className="font-body-sm text-body-sm text-on-surface-variant mt-1">{t.dashboard.pageHint}</p>
        </div>
        {!creating && (
          <button
            type="button"
            className="shrink-0 flex items-center gap-1.5 px-space-md py-space-sm btn btn-primary"
            onClick={() => setCreating(true)}
          >
            <Icon name="add_circle" size={18} />
            {t.dashboard.newTitle}
          </button>
        )}
      </div>

      {error && (
        <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
          <Icon name="error" size={18} />
          {error}
        </div>
      )}

      {creating && (
        <NewDashboardForm services={allServices} onCreated={handleCreated} onCancel={() => setCreating(false)} />
      )}

      {dashboards === null ? (
        <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
          <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
          {t.dashboard.loading}
        </div>
      ) : dashboards.length === 0 && !creating ? (
        <div className="card flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant text-center">
          <Icon name="monitoring" size={28} />
          <span className="font-body-md text-body-md">{t.dashboard.empty}</span>
          <span className="font-body-sm text-body-sm max-w-md">{t.dashboard.emptyHint}</span>
        </div>
      ) : (
        dashboards.map((d) => (
          <DashboardCard
            key={d.id}
            dashboard={d}
            allServices={allServices}
            state={d.scope === "SERVICE" ? states.find((s) => s.serviceName === d.serviceName) : undefined}
            onDelete={handleDelete}
          />
        ))
      )}
    </div>
  );
}
