import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  fetchAlertRules,
  fetchIncident,
  fetchIncidentLibrary,
  investigateIncident,
  NoAiModelError,
  fetchIncidents,
  fetchMembers,
  fetchSimilarIncidents,
  reviewIncident,
  resetAlertRule,
  saveAlertRule,
  type AlertRule,
  type Incident,
  type IncidentReview,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { fillTemplate, formatDuration } from "../durations";
import FlowsView from "./FlowsView";
import AnimatedLogo from "../components/AnimatedLogo";

const REFRESH_MS = 30000;

type Tab = "live" | "library" | "flows";

/** userId -> display name of project members, for "reviewed by". */
const NamesContext = createContext<Map<string, string>>(new Map());

/**
 * Incidents MoSes detected in the current environment by counting (error spikes, services that
 * went DOWN), the history of resolved ones, the per-service alert rules behind them - and the
 * library: every incident with what the team concluded about it, searchable across environments.
 */
export default function IncidentsPage() {
  const { t } = useLanguage();
  const { currentProject, currentEnvironment } = useProjects();
  const [tab, setTab] = useState<Tab>("live");
  const [names, setNames] = useState<Map<string, string>>(new Map());
  const c = t.incidents;

  useEffect(() => {
    if (!currentProject) return;
    fetchMembers(currentProject.id)
      .then((m) => setNames(new Map(m.map((x) => [x.userId, x.name]))))
      .catch(() => setNames(new Map()));
  }, [currentProject]);

  const tabClass = (active: boolean) =>
    `px-space-md py-1 rounded-md font-label-md text-label-md transition-all ${
      active ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
    }`;

  return (
    <NamesContext.Provider value={names}>
      <div className="flex flex-col gap-space-md">
        <div className="flex flex-wrap items-end justify-between gap-space-sm">
          <div>
            <h1 className="font-headline text-headline-lg text-on-surface">{t.nav.incidents}</h1>
            <p className="font-body-sm text-body-sm text-on-surface-variant mt-1">
              {tab === "live" ? fillTemplate(c.pageHint, { env: currentEnvironment ?? "" }) : tab === "library" ? c.libraryHint : c.flowsHint}
            </p>
          </div>
          <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="tablist">
            <button type="button" role="tab" aria-selected={tab === "live"} className={tabClass(tab === "live")} onClick={() => setTab("live")}>
              {c.tabLive}
            </button>
            <button type="button" role="tab" aria-selected={tab === "flows"} className={tabClass(tab === "flows")} onClick={() => setTab("flows")}>
              {c.tabFlows}
            </button>
            <button type="button" role="tab" aria-selected={tab === "library"} className={tabClass(tab === "library")} onClick={() => setTab("library")}>
              {c.tabLibrary}
            </button>
          </div>
        </div>
        {tab === "live" ? <LiveView /> : tab === "library" ? <LibraryView /> : <FlowsView />}
      </div>
    </NamesContext.Provider>
  );
}

function LiveView() {
  const { t } = useLanguage();
  const { currentProject, currentEnvironment, canEdit } = useProjects();
  const [incidents, setIncidents] = useState<Incident[] | null>(null);
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const c = t.incidents;

  const load = useCallback(() => {
    Promise.all([fetchIncidents(), fetchAlertRules()])
      .then(([i, r]) => {
        setIncidents(i);
        setRules(r);
        setError(null);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    if (!currentProject) return;
    setIncidents(null);
    setRules(null);
    load();
    const id = setInterval(load, REFRESH_MS);
    return () => clearInterval(id);
  }, [currentProject, currentEnvironment, load]);

  if (error && incidents === null) return <LoadError message={error} />;
  if (incidents === null || rules === null) return <Loading />;

  const open = incidents.filter((i) => i.state === "OPEN");
  const closed = incidents.filter((i) => i.state === "CLOSED");

  return (
    <>
      <section className="flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-sm text-on-surface flex items-center gap-space-xs">
          {c.openTitle}
          {open.length > 0 && (
            <span className="px-2 rounded-full bg-error text-on-error font-label-sm text-label-sm">{open.length}</span>
          )}
        </h2>
        {open.length === 0 ? (
          <div className="card flex items-center gap-space-sm text-on-surface-variant font-body-md text-body-md">
            <Icon name="check_circle" size={20} className="text-secondary" />
            {c.noneOpen}
          </div>
        ) : (
          open.map((i) => <IncidentCard key={i.id} incident={i} onChanged={load} />)
        )}
      </section>

      <RulesCard rules={rules} canEdit={canEdit} onSaved={load} />

      <section className="flex flex-col gap-space-sm">
        <h2 className="font-headline text-headline-sm text-on-surface">{c.historyTitle}</h2>
        {closed.length === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{c.noHistory}</p>
        ) : (
          closed.map((i) => <IncidentCard key={i.id} incident={i} onChanged={load} />)
        )}
      </section>
    </>
  );
}

const REVIEWS: IncidentReview[] = ["SOLVED", "UNSOLVED", "FALSE_ALARM", "UNREVIEWED"];

function LibraryView() {
  const { t } = useLanguage();
  const { currentProject, currentEnvironment } = useProjects();
  const c = t.incidents;
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<IncidentReview | "">("");
  const [allEnvironments, setAllEnvironments] = useState(true);
  const [results, setResults] = useState<Incident[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(0);

  const load = useCallback(() => {
    const request = ++latest.current;
    fetchIncidentLibrary({ q, status: status || undefined, allEnvironments })
      .then((r) => {
        if (request !== latest.current) return;
        setResults(r);
        setError(null);
      })
      .catch((e) => request === latest.current && setError((e as Error).message));
  }, [q, status, allEnvironments]);

  // Searching as you type, a moment after the last key.
  useEffect(() => {
    if (!currentProject) return;
    const id = setTimeout(load, 250);
    return () => clearTimeout(id);
  }, [currentProject, currentEnvironment, load]);

  return (
    <div className="flex flex-col gap-space-sm">
      <div className="card flex flex-wrap items-center gap-space-sm">
        <div className="flex items-center gap-space-xs flex-1 min-w-[16rem] bg-surface-container-high rounded-lg px-space-sm">
          <Icon name="search" size={18} className="text-on-surface-variant" />
          <input
            className="flex-1 bg-transparent py-1.5 font-body-md text-body-md text-on-surface outline-none"
            placeholder={c.librarySearch}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label={c.librarySearch}
          />
        </div>
        <select
          className="bg-surface-container-high rounded-lg px-space-sm py-1.5 font-body-sm text-body-sm text-on-surface"
          value={status}
          onChange={(e) => setStatus(e.target.value as IncidentReview | "")}
          aria-label={c.review}
        >
          <option value="">{c.libraryAllStatuses}</option>
          {REVIEWS.map((r) => (
            <option key={r} value={r}>
              {c.reviewStatus[r]}
            </option>
          ))}
        </select>
        <label className="flex items-center gap-1.5 font-body-sm text-body-sm text-on-surface-variant">
          <input type="checkbox" className="accent-secondary" checked={allEnvironments} onChange={(e) => setAllEnvironments(e.target.checked)} />
          {c.libraryAllEnvs}
        </label>
      </div>
      {error ? (
        <LoadError message={error} />
      ) : results === null ? (
        <Loading />
      ) : results.length === 0 ? (
        <p className="font-body-md text-body-md text-on-surface-variant">{c.libraryEmpty}</p>
      ) : (
        results.map((i) => <IncidentCard key={i.id} incident={i} onChanged={load} showEnvironment />)
      )}
    </div>
  );
}

function Loading() {
  const { t } = useLanguage();
  return (
    <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
      <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
      {t.incidents.loading}
    </div>
  );
}

function LoadError({ message }: { message: string }) {
  const { t } = useLanguage();
  return (
    <div className="flex items-center gap-space-sm bg-error-container/20 text-error px-space-md py-space-sm rounded-xl font-body-md text-body-md">
      <Icon name="error" size={18} />
      {t.incidents.loadFailed}: {message}
    </div>
  );
}

const REVIEW_STYLE: Record<IncidentReview, string> = {
  SOLVED: "bg-secondary/15 text-secondary",
  UNSOLVED: "bg-tertiary/15 text-tertiary",
  FALSE_ALARM: "bg-surface-container-high text-on-surface-variant",
  UNREVIEWED: "bg-surface-container-high text-on-surface-variant",
};

interface CardProps {
  incident: Incident;
  onChanged: () => void;
  /** In the library, which environment it happened in. */
  showEnvironment?: boolean;
}

function IncidentCard({ incident: given, onChanged, showEnvironment }: CardProps) {
  const { t, lang } = useLanguage();
  const c = t.incidents;
  const [panel, setPanel] = useState<"review" | "similar" | null>(null);
  const { canEdit } = useProjects();
  // The incident as last fetched here: polled while an AI investigation runs.
  const [i, setCurrent] = useState(given);
  useEffect(() => setCurrent(given), [given]);
  const [reviewDraft, setReviewDraft] = useState<{ rootCause: string; resolution: string } | null>(null);
  const [aiError, setAiError] = useState<{ noModel: boolean; text: string } | null>(null);
  const running = i.aiInvestigation?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const id = setInterval(() => {
      fetchIncident(i.id)
        .then((fresh) => {
          setCurrent(fresh);
          if (fresh.aiInvestigation?.status !== "RUNNING") onChanged();
        })
        .catch(() => undefined);
    }, 3000);
    return () => clearInterval(id);
  }, [running, i.id, onChanged]);

  async function startInvestigation() {
    setAiError(null);
    try {
      await investigateIncident(i.id, lang);
      setCurrent(await fetchIncident(i.id));
    } catch (e) {
      setAiError({ noModel: e instanceof NoAiModelError, text: (e as Error).message });
    }
  }
  const isOpen = i.state === "OPEN";
  const locale = lang === "he" ? "he-IL" : "en-US";
  const opened = new Date(i.openedAt);
  const end = i.closedAt ? new Date(i.closedAt) : new Date();
  const duration = formatDuration((end.getTime() - opened.getTime()) / 1000, lang);
  const spike = i.kind === "ERROR_SPIKE";
  const flow = i.kind === "FLOW_BROKEN";

  return (
    <div className={`card flex flex-col gap-space-xs border ${isOpen ? "border-error/50" : "border-transparent"}`}>
      <div className="flex flex-wrap items-center gap-space-sm">
        <Icon
          name={spike ? "error" : flow ? "alt_route" : "cloud_off"}
          size={20}
          className={isOpen ? "text-error" : "text-on-surface-variant"}
        />
        <span className="font-code text-code-md text-on-surface">{i.serviceName}</span>
        <span className="px-2 py-0.5 rounded-full bg-surface-container-high font-label-sm text-label-sm text-on-surface-variant">
          {spike ? c.kindSpike : flow ? c.kindFlow : c.kindDown}
        </span>
        <span
          className={`px-2 py-0.5 rounded-full font-label-sm text-label-sm ${
            isOpen ? "bg-error-container/40 text-error" : "bg-secondary/15 text-secondary"
          }`}
        >
          {isOpen ? c.stateOpen : c.stateClosed}
        </span>
        {i.reviewStatus !== "UNREVIEWED" && (
          <span className={`px-2 py-0.5 rounded-full font-label-sm text-label-sm ${REVIEW_STYLE[i.reviewStatus]}`}>
            {c.reviewStatus[i.reviewStatus]}
          </span>
        )}
        {showEnvironment && (
          <span className="px-2 py-0.5 rounded-full bg-surface-container-high font-label-sm text-label-sm text-on-surface-variant">
            {fillTemplate(c.envTag, { env: i.environment })}
          </span>
        )}
        <span className="ms-auto font-body-sm text-body-sm text-on-surface-variant">
          {opened.toLocaleString(locale, { dateStyle: "short", timeStyle: "short" })} ·{" "}
          {fillTemplate(isOpen ? c.ongoing : c.lasted, { duration })}
        </span>
      </div>

      <p className="font-body-md text-body-md text-on-surface">
        {spike
          ? fillTemplate(isOpen ? c.spikeDetail : c.spikeDetailClosed, {
              errors: i.currentErrors,
              window: i.windowMinutes,
              peak: i.peakErrors,
              baseline: Math.round(i.baselineErrors),
            })
          : flow
            ? fillTemplate(isOpen ? c.flowDetail : c.flowDetailClosed, {
                entry: i.flowEntry ?? "",
                target: i.serviceName,
                broken: i.brokenTraces,
                checked: i.checkedTraces,
                peak: i.peakBrokenTraces,
                window: i.windowMinutes,
                seconds: i.flowMaxSeconds ?? 0,
              })
            : c.downDetail}
      </p>

      {flow && i.flowRouteExample && (
        <span dir="ltr" className="self-start font-code text-code-sm bg-surface-container rounded px-1.5 py-0.5 text-on-surface-variant">
          {i.flowRouteExample}
        </span>
      )}

      {flow && i.brokenExamples.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="font-label-sm text-label-sm text-on-surface-variant">{c.brokenExamples}</span>
          {i.brokenExamples.map((b) => (
            <div key={b.traceId} className="flex flex-wrap items-center gap-space-sm bg-surface-container rounded-lg px-space-sm py-1 font-body-sm text-body-sm">
              <span className="text-error">{c.reason[b.reason]}</span>
              <span className="text-on-surface-variant">{fillTemplate(c.stoppedAt, { service: b.stoppedAt })}</span>
              <Link
                to={`../search?trace=${encodeURIComponent(b.traceId)}`}
                relative="path"
                className="ms-auto font-label-sm text-label-sm text-secondary hover:underline"
              >
                {c.viewTrace}
              </Link>
            </div>
          ))}
        </div>
      )}

      {i.topErrors.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="font-label-sm text-label-sm text-on-surface-variant">{c.topErrors}</span>
          {i.topErrors.map((e, idx) => (
            <div key={idx} className="flex items-start gap-space-sm bg-surface-container rounded-lg px-space-sm py-1">
              <span className="font-code text-code-sm text-error shrink-0">×{e.count}</span>
              <span dir="ltr" className="font-code text-code-sm text-on-surface break-all line-clamp-2 flex-1">
                {e.message}
              </span>
              {e.traceId && (
                <Link
                  to={`../search?trace=${encodeURIComponent(e.traceId)}`}
                  relative="path"
                  className="shrink-0 font-label-sm text-label-sm text-secondary hover:underline"
                >
                  {c.viewTrace}
                </Link>
              )}
            </div>
          ))}
        </div>
      )}

      {(i.rootCause || i.resolution) && panel !== "review" && <ReviewSummary incident={i} />}

      <div className="flex flex-wrap items-center gap-space-md">
        <Link
          to={`../search?service=${encodeURIComponent(i.serviceName)}`}
          relative="path"
          className="flex items-center gap-1 font-label-sm text-label-sm text-secondary hover:underline"
        >
          <Icon name="terminal" size={16} />
          {c.viewLogs}
        </Link>
        <button
          type="button"
          className={`flex items-center gap-1 font-label-sm text-label-sm hover:underline ${panel === "review" ? "text-on-surface" : "text-secondary"}`}
          onClick={() => setPanel(panel === "review" ? null : "review")}
          aria-expanded={panel === "review"}
        >
          <Icon name="edit_note" size={16} />
          {c.review}
        </button>
        {canEdit && !running && (
          <button
            type="button"
            className="flex items-center gap-1 font-label-sm text-label-sm text-tertiary hover:underline"
            onClick={() => void startInvestigation()}
          >
            <Icon name="auto_awesome" size={16} />
            {i.aiInvestigation ? c.investigateAgain : c.investigate}
          </button>
        )}
        <button
          type="button"
          className={`flex items-center gap-1 font-label-sm text-label-sm hover:underline ${panel === "similar" ? "text-on-surface" : "text-secondary"}`}
          onClick={() => setPanel(panel === "similar" ? null : "similar")}
          aria-expanded={panel === "similar"}
        >
          <Icon name="history" size={16} />
          {c.seenBefore}
        </button>
      </div>

      {aiError && (
        <p className="font-body-sm text-body-sm text-error">
          {aiError.noModel ? (
            <>
              {c.aiNoModel}{" "}
              <Link to="../settings" relative="path" className="underline">
                {c.aiNoModelLink}
              </Link>
            </>
          ) : (
            aiError.text
          )}
        </p>
      )}
      {running && (
        <div className="flex items-center gap-space-sm bg-surface-container rounded-xl px-space-sm py-space-xs font-body-sm text-body-sm text-on-surface-variant">
          <AnimatedLogo size={28} loop />
          {c.investigating}
        </div>
      )}
      {i.aiInvestigation && !running && (
        <AiInvestigationPanel
          incident={i}
          onCopy={
            canEdit && i.aiInvestigation.status === "DONE"
              ? () => {
                  setReviewDraft({
                    rootCause: i.aiInvestigation?.rootCause ?? i.aiInvestigation?.summary ?? "",
                    resolution: i.aiInvestigation?.suggestedFix ?? "",
                  });
                  setPanel("review");
                }
              : undefined
          }
        />
      )}

      {panel === "review" && (
        <ReviewForm key={reviewDraft ? JSON.stringify(reviewDraft) : "saved"} incident={i} draft={reviewDraft} onSaved={onChanged} />
      )}
      {panel === "similar" && <SimilarList incidentId={i.id} />}
    </div>
  );
}

function ReviewSummary({ incident: i }: { incident: Incident }) {
  const { t, lang } = useLanguage();
  const names = useContext(NamesContext);
  const c = t.incidents;
  const locale = lang === "he" ? "he-IL" : "en-US";
  return (
    <div className="flex flex-col gap-1 border-s-2 border-secondary/60 ps-space-sm">
      {i.rootCause && (
        <div className="font-body-sm text-body-sm">
          <span className="text-on-surface-variant">{c.rootCause}: </span>
          <span dir="auto" className="text-on-surface whitespace-pre-wrap">{i.rootCause}</span>
        </div>
      )}
      {i.resolution && (
        <div className="font-body-sm text-body-sm">
          <span className="text-on-surface-variant">{c.resolution}: </span>
          <span dir="auto" className="text-on-surface whitespace-pre-wrap">{i.resolution}</span>
        </div>
      )}
      {i.reviewedBy && i.reviewedAt && (
        <span className="font-label-sm text-[11px] text-on-surface-variant opacity-80">
          {fillTemplate(c.reviewedBy, {
            name: names.get(i.reviewedBy) ?? c.someone,
            time: new Date(i.reviewedAt).toLocaleString(locale, { dateStyle: "short", timeStyle: "short" }),
          })}
        </span>
      )}
    </div>
  );
}

function AiInvestigationPanel({ incident: i, onCopy }: { incident: Incident; onCopy?: () => void }) {
  const { t, lang } = useLanguage();
  const names = useContext(NamesContext);
  const c = t.incidents;
  const ai = i.aiInvestigation!;
  const locale = lang === "he" ? "he-IL" : "en-US";
  if (ai.status === "FAILED") {
    return (
      <div className="flex items-center gap-space-sm bg-error-container/15 rounded-xl px-space-sm py-space-xs font-body-sm text-body-sm text-error">
        <Icon name="error" size={16} />
        {c.aiFailed}: {ai.error}
      </div>
    );
  }
  const confidenceStyle =
    ai.confidence === "high" ? "text-secondary" : ai.confidence === "medium" ? "text-tertiary" : "text-on-surface-variant";
  return (
    <div className="flex flex-col gap-space-xs bg-surface-container rounded-xl p-space-sm border border-tertiary/30">
      <div className="flex flex-wrap items-center gap-space-sm">
        <AnimatedLogo size={24} />
        <span className="font-label-md text-label-md text-on-surface">{c.aiTitle}</span>
        {ai.confidence && <span className={`font-label-sm text-label-sm ${confidenceStyle}`}>{c.aiConfidence[ai.confidence]}</span>}
        {onCopy && (
          <button
            type="button"
            className="ms-auto flex items-center gap-1 px-space-sm py-0.5 rounded-md bg-surface-container-high hover:bg-surface-bright font-label-sm text-label-sm text-secondary"
            onClick={onCopy}
          >
            <Icon name="content_paste_go" size={16} />
            {c.aiCopy}
          </button>
        )}
      </div>
      {ai.summary && (
        <p dir="auto" className="font-body-md text-body-md text-on-surface whitespace-pre-wrap">
          {ai.summary}
        </p>
      )}
      {ai.rootCause && (
        <div className="font-body-sm text-body-sm">
          <span className="text-on-surface-variant">{c.aiRootCause}: </span>
          <span dir="auto" className="text-on-surface whitespace-pre-wrap">{ai.rootCause}</span>
        </div>
      )}
      {ai.suggestedFix && (
        <div className="font-body-sm text-body-sm">
          <span className="text-on-surface-variant">{c.aiFix}: </span>
          <span dir="auto" className="text-on-surface whitespace-pre-wrap">{ai.suggestedFix}</span>
        </div>
      )}
      {ai.evidence && ai.evidence.length > 0 && (
        <div className="font-body-sm text-body-sm">
          <span className="text-on-surface-variant">{c.aiEvidence}:</span>
          <ul className="list-disc ps-space-md">
            {ai.evidence.map((e, idx) => (
              <li key={idx} dir="auto" className="text-on-surface break-words">
                {e}
              </li>
            ))}
          </ul>
        </div>
      )}
      <span className="font-label-sm text-[11px] text-on-surface-variant opacity-80">
        {c.aiUnconfirmed}{" "}
        {fillTemplate(c.aiMeta, {
          model: ai.model ?? "",
          name: (ai.requestedBy && names.get(ai.requestedBy)) || c.someone,
          time: ai.finishedAt ? new Date(ai.finishedAt).toLocaleString(locale, { dateStyle: "short", timeStyle: "short" }) : "",
        })}
      </span>
    </div>
  );
}

function ReviewForm({
  incident: i,
  draft,
  onSaved,
}: {
  incident: Incident;
  /** Prefilled text (from the AI investigation) instead of what's saved. */
  draft?: { rootCause: string; resolution: string } | null;
  onSaved: () => void;
}) {
  const { t } = useLanguage();
  const { canEdit } = useProjects();
  const c = t.incidents;
  // Starts from what's saved; later refreshes of the list don't overwrite what's being typed.
  const [status, setStatus] = useState<IncidentReview>(i.reviewStatus === "UNREVIEWED" ? "SOLVED" : i.reviewStatus);
  const [rootCause, setRootCause] = useState(draft?.rootCause ?? i.rootCause ?? "");
  const [resolution, setResolution] = useState(draft?.resolution ?? i.resolution ?? "");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      await reviewIncident(i.id, { status, rootCause, resolution });
      setMessage({ ok: true, text: c.reviewSaved });
      onSaved();
    } catch {
      setMessage({ ok: false, text: c.reviewFailed });
    } finally {
      setBusy(false);
    }
  }

  const field =
    "w-full bg-surface-container-high rounded-lg px-space-sm py-1.5 font-body-sm text-body-sm text-on-surface outline-none border border-transparent focus:border-secondary disabled:opacity-60";

  return (
    <div className="flex flex-col gap-space-sm bg-surface-container rounded-xl p-space-sm">
      {!canEdit && <p className="font-body-sm text-body-sm text-tertiary">{c.viewerReviewHint}</p>}
      <div className="flex flex-wrap gap-space-xs" role="radiogroup" aria-label={c.review}>
        {REVIEWS.filter((r) => r !== "UNREVIEWED").map((r) => (
          <button
            key={r}
            type="button"
            role="radio"
            aria-checked={status === r}
            disabled={!canEdit || busy}
            className={`px-space-sm py-0.5 rounded-full font-label-sm text-label-sm border transition-all ${
              status === r ? `${REVIEW_STYLE[r]} border-current` : "border-outline-variant/40 text-on-surface-variant"
            }`}
            onClick={() => setStatus(r)}
          >
            {c.reviewStatus[r]}
          </button>
        ))}
      </div>
      <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
        {c.rootCause}
        <textarea
          dir="auto"
          rows={2}
          maxLength={4000}
          className={field}
          placeholder={c.rootCausePlaceholder}
          value={rootCause}
          disabled={!canEdit || busy}
          onChange={(e) => setRootCause(e.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
        {c.resolution}
        <textarea
          dir="auto"
          rows={3}
          maxLength={4000}
          className={field}
          placeholder={c.resolutionPlaceholder}
          value={resolution}
          disabled={!canEdit || busy}
          onChange={(e) => setResolution(e.target.value)}
        />
      </label>
      {canEdit && (
        <div className="flex items-center gap-space-sm">
          <button
            type="button"
            disabled={busy}
            className="px-space-md py-1 rounded-lg bg-secondary text-on-secondary font-label-md text-label-md disabled:opacity-50"
            onClick={() => void save()}
          >
            {c.saveReview}
          </button>
          {message && (
            <span className={`font-body-sm text-body-sm ${message.ok ? "text-secondary" : "text-error"}`}>{message.text}</span>
          )}
        </div>
      )}
    </div>
  );
}

function SimilarList({ incidentId }: { incidentId: string }) {
  const { t, lang } = useLanguage();
  const c = t.incidents;
  const [items, setItems] = useState<Incident[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const locale = lang === "he" ? "he-IL" : "en-US";

  useEffect(() => {
    let cancelled = false;
    fetchSimilarIncidents(incidentId)
      .then((r) => !cancelled && setItems(r))
      .catch((e) => !cancelled && setError((e as Error).message));
    return () => {
      cancelled = true;
    };
  }, [incidentId]);

  if (error) return <LoadError message={error} />;
  if (items === null) return <Loading />;
  if (items.length === 0) return <p className="font-body-sm text-body-sm text-on-surface-variant">{c.noSimilar}</p>;

  return (
    <div className="flex flex-col gap-space-xs bg-surface-container rounded-xl p-space-sm">
      <span className="font-label-sm text-label-sm text-on-surface-variant">{fillTemplate(c.seenBeforeCount, { n: items.length })}</span>
      {items.map((s) => (
        <div key={s.id} className="flex flex-col gap-1 border-t border-outline-variant/30 pt-space-xs first:border-t-0 first:pt-0">
          <div className="flex flex-wrap items-center gap-space-sm font-body-sm text-body-sm">
            <span className="font-code text-code-sm text-on-surface">{s.serviceName}</span>
            <span className={`px-2 py-0.5 rounded-full font-label-sm text-label-sm ${REVIEW_STYLE[s.reviewStatus]}`}>
              {c.reviewStatus[s.reviewStatus]}
            </span>
            <span className="px-2 py-0.5 rounded-full bg-surface-container-high font-label-sm text-label-sm text-on-surface-variant">
              {fillTemplate(c.envTag, { env: s.environment })}
            </span>
            <span className="ms-auto text-on-surface-variant">
              {new Date(s.openedAt).toLocaleString(locale, { dateStyle: "short", timeStyle: "short" })}
            </span>
          </div>
          {s.rootCause || s.resolution ? (
            <ReviewSummary incident={s} />
          ) : (
            s.topErrors[0] && (
              <span dir="ltr" className="font-code text-code-sm text-on-surface-variant line-clamp-1">{s.topErrors[0].message}</span>
            )
          )}
        </div>
      ))}
    </div>
  );
}

function RulesCard({ rules, canEdit, onSaved }: { rules: AlertRule[]; canEdit: boolean; onSaved: () => void }) {
  const { t } = useLanguage();
  const c = t.incidents;
  const window = rules[0]?.windowMinutes ?? 5;

  return (
    <section className="card flex flex-col gap-space-sm">
      <h2 className="font-headline text-headline-sm text-on-surface flex items-center gap-space-xs">
        <Icon name="tune" size={20} className="text-secondary" />
        {c.rulesTitle}
      </h2>
      <p className="font-body-sm text-body-sm text-on-surface-variant">{fillTemplate(c.rulesHint, { window })}</p>
      {!canEdit && <p className="font-body-sm text-body-sm text-tertiary">{c.viewerHint}</p>}
      {rules.length === 0 ? (
        <p className="font-body-md text-body-md text-on-surface-variant">{c.noServices}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full font-body-sm text-body-sm">
            <thead>
              <tr className="text-on-surface-variant text-start">
                <th className="text-start font-label-sm text-label-sm py-1 pe-space-sm">{c.colService}</th>
                <th className="text-start font-label-sm text-label-sm py-1 pe-space-sm">{c.colMinErrors}</th>
                <th className="text-start font-label-sm text-label-sm py-1 pe-space-sm">{c.colFactor}</th>
                <th className="text-start font-label-sm text-label-sm py-1 pe-space-sm">{c.colMuted}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <RuleRow key={r.serviceName} rule={r} canEdit={canEdit} onSaved={onSaved} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function RuleRow({ rule, canEdit, onSaved }: { rule: AlertRule; canEdit: boolean; onSaved: () => void }) {
  const { t } = useLanguage();
  const c = t.incidents;
  const [minErrors, setMinErrors] = useState(String(rule.minErrors));
  const [factor, setFactor] = useState(String(rule.factor));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setMinErrors(String(rule.minErrors));
    setFactor(String(rule.factor));
  }, [rule.minErrors, rule.factor]);

  const min = Number(minErrors);
  const fac = Number(factor);
  const valid = Number.isInteger(min) && min >= 1 && min <= 100000 && Number.isFinite(fac) && fac >= 1 && fac <= 100;
  const dirty = min !== rule.minErrors || fac !== rule.factor;

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onSaved();
    } catch {
      setError(c.saveFailed);
    } finally {
      setBusy(false);
    }
  }

  const input =
    "w-20 bg-surface-container-high rounded-md px-2 py-1 font-code text-code-sm text-on-surface disabled:opacity-60 border border-transparent focus:border-secondary outline-none";

  return (
    <tr className={`border-t border-outline-variant/30 ${rule.muted ? "opacity-60" : ""}`}>
      <td className="py-1.5 pe-space-sm">
        <span className="font-code text-code-sm text-on-surface">{rule.serviceName}</span>{" "}
        <span className={`font-label-sm text-label-sm ${rule.custom ? "text-tertiary" : "text-on-surface-variant/70"}`}>
          {rule.custom ? c.custom : c.defaults}
        </span>
      </td>
      <td className="py-1.5 pe-space-sm">
        <input
          className={input}
          type="number"
          min={1}
          max={100000}
          step={1}
          value={minErrors}
          disabled={!canEdit || busy}
          onChange={(e) => setMinErrors(e.target.value)}
          aria-label={`${c.colMinErrors} ${rule.serviceName}`}
        />
      </td>
      <td className="py-1.5 pe-space-sm">
        <input
          className={input}
          type="number"
          min={1}
          max={100}
          step={0.5}
          value={factor}
          disabled={!canEdit || busy}
          onChange={(e) => setFactor(e.target.value)}
          aria-label={`${c.colFactor} ${rule.serviceName}`}
        />
      </td>
      <td className="py-1.5 pe-space-sm">
        <input
          type="checkbox"
          className="accent-secondary w-4 h-4"
          checked={rule.muted}
          disabled={!canEdit || busy}
          onChange={(e) => void run(() => saveAlertRule(rule.serviceName, { muted: e.target.checked }))}
          aria-label={`${c.colMuted} ${rule.serviceName}`}
        />
      </td>
      <td className="py-1.5">
        {canEdit && (
          <div className="flex items-center gap-space-xs justify-end">
            {dirty && (
              <button
                type="button"
                disabled={!valid || busy}
                className="px-space-sm py-0.5 rounded-md bg-secondary text-on-secondary font-label-sm text-label-sm disabled:opacity-50"
                onClick={() => void run(() => saveAlertRule(rule.serviceName, { minErrors: min, factor: fac }))}
              >
                {c.save}
              </button>
            )}
            {rule.custom && (
              <button
                type="button"
                disabled={busy}
                className="p-1 rounded-md text-on-surface-variant hover:text-on-surface"
                title={c.reset}
                aria-label={c.reset}
                onClick={() => void run(() => resetAlertRule(rule.serviceName))}
              >
                <Icon name="restart_alt" size={16} />
              </button>
            )}
            {error && <span className="text-error font-label-sm text-label-sm">{error}</span>}
          </div>
        )}
      </td>
    </tr>
  );
}
