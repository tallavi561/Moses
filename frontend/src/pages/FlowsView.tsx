import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  createFlow,
  deleteFlow,
  fetchFlows,
  fetchProcessState,
  learnFlows,
  updateFlow,
  type TraceFlow,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { fillTemplate } from "../durations";

const REFRESH_MS = 30000;

/**
 * Request flows of the current environment: what MoSes learned from the traces and suggests,
 * what the team approved (checked every minute), and a form to add one by hand.
 */
export default function FlowsView() {
  const { t } = useLanguage();
  const { currentProject, currentEnvironment, canEdit } = useProjects();
  const c = t.incidents;
  const [flows, setFlows] = useState<TraceFlow[] | null>(null);
  const [services, setServices] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [learning, setLearning] = useState(false);
  const [learnMessage, setLearnMessage] = useState<string | null>(null);
  const [showRejected, setShowRejected] = useState(false);

  const load = useCallback(() => {
    fetchFlows()
      .then((f) => {
        setFlows(f);
        setError(null);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    if (!currentProject) return;
    setFlows(null);
    load();
    fetchProcessState()
      .then((s) => setServices(s.map((x) => x.serviceName).sort()))
      .catch(() => setServices([]));
    const id = setInterval(load, REFRESH_MS);
    return () => clearInterval(id);
  }, [currentProject, currentEnvironment, load]);

  async function learn() {
    setLearning(true);
    setLearnMessage(null);
    try {
      const r = await learnFlows();
      setFlows(r.flows);
      setLearnMessage(r.added > 0 ? fillTemplate(c.learnedSome, { n: r.added }) : fillTemplate(c.learnedNone, { n: r.minSamples }));
    } catch (e) {
      setLearnMessage((e as Error).message);
    } finally {
      setLearning(false);
    }
  }

  if (error && flows === null) {
    return (
      <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
        <Icon name="error" size={18} />
        {c.loadFailed}: {error}
      </div>
    );
  }
  if (flows === null) {
    return (
      <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
        <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
        {c.loading}
      </div>
    );
  }

  const suggested = flows.filter((f) => f.status === "SUGGESTED");
  const checked = flows.filter((f) => f.status === "ACTIVE" || f.status === "PAUSED");
  const rejected = flows.filter((f) => f.status === "REJECTED");

  return (
    <div className="flex flex-col gap-space-md">
      <div className="flex flex-wrap items-center gap-space-sm">
        {canEdit ? (
          <button
            type="button"
            disabled={learning}
            className="flex items-center gap-1.5 px-space-md py-1 rounded-lg bg-surface-container-high hover:bg-surface-bright text-on-surface font-label-md text-label-md disabled:opacity-60"
            onClick={() => void learn()}
          >
            <Icon name={learning ? "progress_activity" : "auto_awesome"} size={16} className={learning ? "animate-spin" : "text-secondary"} />
            {learning ? c.learning : c.learnNow}
          </button>
        ) : (
          <p className="font-body-sm text-body-sm text-tertiary">{c.viewerFlowsHint}</p>
        )}
        {learnMessage && <span className="font-body-sm text-body-sm text-on-surface-variant">{learnMessage}</span>}
      </div>

      <section className="flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-sm text-on-surface flex items-center gap-space-xs">
          <Icon name="lightbulb" size={20} className="text-tertiary" />
          {c.suggestionsTitle}
          {suggested.length > 0 && (
            <span className="px-2 rounded-full bg-tertiary/20 text-tertiary font-label-sm text-label-sm">{suggested.length}</span>
          )}
        </h2>
        {suggested.length === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{c.noSuggestions}</p>
        ) : (
          suggested.map((f) => <FlowCard key={f.id} flow={f} canEdit={canEdit} onChanged={load} />)
        )}
      </section>

      <section className="flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-sm text-on-surface flex items-center gap-space-xs">
          <Icon name="alt_route" size={20} className="text-secondary" />
          {c.activeTitle}
        </h2>
        {checked.length === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{c.noActive}</p>
        ) : (
          checked.map((f) => <FlowCard key={f.id} flow={f} canEdit={canEdit} onChanged={load} />)
        )}
      </section>

      {canEdit && <AddFlowForm services={services} onAdded={load} />}

      {rejected.length > 0 && (
        <section className="flex flex-col gap-space-sm">
          <button
            type="button"
            className="self-start flex items-center gap-1 font-label-md text-label-md text-on-surface-variant hover:text-on-surface"
            onClick={() => setShowRejected(!showRejected)}
            aria-expanded={showRejected}
          >
            <Icon name={showRejected ? "expand_less" : "expand_more"} size={18} />
            {fillTemplate(c.rejectedTitle, { n: rejected.length })}
          </button>
          {showRejected && rejected.map((f) => <FlowCard key={f.id} flow={f} canEdit={canEdit} onChanged={load} />)}
        </section>
      )}
    </div>
  );
}

function FlowCard({ flow: f, canEdit, onChanged }: { flow: TraceFlow; canEdit: boolean; onChanged: () => void }) {
  const { t } = useLanguage();
  const c = t.incidents;
  const [maxSeconds, setMaxSeconds] = useState(String(f.maxSeconds));
  const [percent, setPercent] = useState(String(f.maxBrokenPercent));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setMaxSeconds(String(f.maxSeconds));
    setPercent(String(f.maxBrokenPercent));
  }, [f.maxSeconds, f.maxBrokenPercent]);

  const sec = Number(maxSeconds);
  const pct = Number(percent);
  const valid = Number.isInteger(sec) && sec >= 1 && sec <= 3600 && Number.isFinite(pct) && pct >= 1 && pct <= 100;
  const dirty = sec !== f.maxSeconds || pct !== f.maxBrokenPercent;

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch {
      setError(c.flowFailed);
    } finally {
      setBusy(false);
    }
  }

  const tuning = dirty && valid ? { maxSeconds: sec, maxBrokenPercent: pct } : {};
  const input =
    "w-20 bg-surface-container-high rounded-md px-2 py-1 font-code text-code-sm text-on-surface disabled:opacity-60 border border-transparent focus:border-secondary outline-none";
  const button = "px-space-sm py-0.5 rounded-md font-label-sm text-label-sm disabled:opacity-50";

  return (
    <div
      className={`card flex flex-col gap-space-xs border ${
        f.status === "SUGGESTED" ? "border-tertiary/40" : f.status === "ACTIVE" ? "border-secondary/30" : "border-transparent opacity-80"
      }`}
    >
      <div className="flex flex-wrap items-center gap-space-sm">
        <span dir="ltr" className="flex items-center gap-1.5 font-code text-code-md text-on-surface">
          {f.entryService}
          <Icon name="arrow_forward" size={16} className="text-secondary" />
          {f.targetService}
        </span>
        {f.status === "PAUSED" && (
          <span className="px-2 py-0.5 rounded-full bg-surface-container-high font-label-sm text-label-sm text-on-surface-variant">{c.paused}</span>
        )}
        <span className="ms-auto font-body-sm text-body-sm text-on-surface-variant">
          {f.status === "ACTIVE" || f.status === "PAUSED"
            ? f.lastCheckedAt && f.lastCheckedTraces !== null
              ? fillTemplate(c.lastCheck, { broken: f.lastBrokenTraces ?? 0, checked: f.lastCheckedTraces })
              : c.neverChecked
            : null}
        </span>
      </div>
      <div className="font-body-sm text-body-sm text-on-surface-variant">
        {f.routeExample ? (
          <span dir="ltr" className="font-code text-code-sm bg-surface-container rounded px-1.5 py-0.5 text-on-surface">
            {f.routeExample}
          </span>
        ) : (
          c.anyRequest
        )}
      </div>
      {f.learnedSamples !== null && f.learnedReachPercent !== null && (
        <div className="flex flex-wrap items-center gap-space-sm font-body-sm text-body-sm text-on-surface-variant">
          {fillTemplate(c.learnedStats, { reach: f.learnedReachPercent, samples: f.learnedSamples, p95: f.learnedP95Seconds ?? 0 })}
          {f.exampleTraceId && (
            <Link
              to={`../search?trace=${encodeURIComponent(f.exampleTraceId)}`}
              relative="path"
              className="font-label-sm text-label-sm text-secondary hover:underline"
            >
              {c.exampleTrace}
            </Link>
          )}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-space-sm">
        <label className="flex items-center gap-1.5 font-label-sm text-label-sm text-on-surface-variant">
          {c.deadline}
          <input
            className={input}
            type="number"
            min={1}
            max={3600}
            value={maxSeconds}
            disabled={!canEdit || busy || f.status === "REJECTED"}
            onChange={(e) => setMaxSeconds(e.target.value)}
          />
        </label>
        <label className="flex items-center gap-1.5 font-label-sm text-label-sm text-on-surface-variant">
          {c.threshold}
          <input
            className={input}
            type="number"
            min={1}
            max={100}
            value={percent}
            disabled={!canEdit || busy || f.status === "REJECTED"}
            onChange={(e) => setPercent(e.target.value)}
          />
        </label>
        {canEdit && (
          <div className="flex flex-wrap items-center gap-space-xs ms-auto">
            {(f.status === "SUGGESTED" || f.status === "REJECTED") && (
              <button
                type="button"
                disabled={busy || !valid}
                className={`${button} bg-secondary text-on-secondary`}
                onClick={() => void run(() => updateFlow(f.id, { status: "ACTIVE", ...tuning }))}
              >
                {c.approve}
              </button>
            )}
            {f.status === "SUGGESTED" && (
              <button
                type="button"
                disabled={busy}
                className={`${button} bg-surface-container-high text-on-surface-variant hover:text-on-surface`}
                onClick={() => void run(() => updateFlow(f.id, { status: "REJECTED" }))}
              >
                {c.reject}
              </button>
            )}
            {(f.status === "ACTIVE" || f.status === "PAUSED") && dirty && (
              <button
                type="button"
                disabled={busy || !valid}
                className={`${button} bg-secondary text-on-secondary`}
                onClick={() => void run(() => updateFlow(f.id, tuning))}
              >
                {c.save}
              </button>
            )}
            {f.status === "ACTIVE" && (
              <button
                type="button"
                disabled={busy}
                className={`${button} bg-surface-container-high text-on-surface-variant hover:text-on-surface`}
                onClick={() => void run(() => updateFlow(f.id, { status: "PAUSED" }))}
              >
                {c.pause}
              </button>
            )}
            {f.status === "PAUSED" && (
              <button
                type="button"
                disabled={busy}
                className={`${button} bg-surface-container-high text-on-surface`}
                onClick={() => void run(() => updateFlow(f.id, { status: "ACTIVE" }))}
              >
                {c.resume}
              </button>
            )}
            {f.status !== "SUGGESTED" && (
              <button
                type="button"
                disabled={busy}
                className="p-1 rounded-md text-on-surface-variant hover:text-error"
                title={c.deleteFlow}
                aria-label={c.deleteFlow}
                onClick={() => void run(() => deleteFlow(f.id))}
              >
                <Icon name="delete" size={16} />
              </button>
            )}
          </div>
        )}
      </div>
      {error && <span className="font-body-sm text-body-sm text-error">{error}</span>}
    </div>
  );
}

function AddFlowForm({ services, onAdded }: { services: string[]; onAdded: () => void }) {
  const { t } = useLanguage();
  const c = t.incidents;
  const [entry, setEntry] = useState("");
  const [target, setTarget] = useState("");
  const [route, setRoute] = useState("");
  const [maxSeconds, setMaxSeconds] = useState("30");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const sec = Number(maxSeconds);
  const valid = entry && target && entry !== target && Number.isInteger(sec) && sec >= 1 && sec <= 3600;

  async function add() {
    setBusy(true);
    setError(null);
    try {
      await createFlow({ entryService: entry, targetService: target, routeExample: route.trim() || undefined, maxSeconds: sec });
      setRoute("");
      onAdded();
    } catch {
      setError(c.flowFailed);
    } finally {
      setBusy(false);
    }
  }

  const select = "bg-surface-container-high rounded-lg px-space-sm py-1.5 font-code text-code-sm text-on-surface";
  return (
    <section className="card flex flex-col gap-space-sm">
      <h2 className="font-headline text-headline-sm text-on-surface flex items-center gap-space-xs">
        <Icon name="add_road" size={20} className="text-secondary" />
        {c.addTitle}
      </h2>
      <div className="flex flex-wrap items-end gap-space-sm">
        <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
          {c.entry}
          <select className={select} value={entry} onChange={(e) => setEntry(e.target.value)}>
            <option value="" />
            {services.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
          {c.target}
          <select className={select} value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="" />
            {services
              .filter((s) => s !== entry)
              .map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
          {c.deadline}
          <input
            className="w-20 bg-surface-container-high rounded-lg px-space-sm py-1.5 font-code text-code-sm text-on-surface"
            type="number"
            min={1}
            max={3600}
            value={maxSeconds}
            onChange={(e) => setMaxSeconds(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 flex-1 min-w-[14rem] font-label-sm text-label-sm text-on-surface-variant">
          {c.routeOptional}
          <input
            dir="ltr"
            className="bg-surface-container-high rounded-lg px-space-sm py-1.5 font-code text-code-sm text-on-surface"
            placeholder={c.routePlaceholder}
            value={route}
            onChange={(e) => setRoute(e.target.value)}
          />
        </label>
        <button
          type="button"
          disabled={!valid || busy}
          className="px-space-md py-1.5 rounded-lg bg-secondary text-on-secondary font-label-md text-label-md disabled:opacity-50"
          onClick={() => void add()}
        >
          {c.add}
        </button>
      </div>
      {error && <span className="font-body-sm text-body-sm text-error">{error}</span>}
    </section>
  );
}
