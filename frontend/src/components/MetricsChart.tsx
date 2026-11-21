import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchErrorRates, fetchMetrics, type DashboardMetric, type MetricPoint, type ServiceMetrics } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { chartColor } from "../chartColors";
import ServiceMultiSelect from "./ServiceMultiSelect";

interface MetricsChartProps {
  /** Every service known in this environment, for the picker. */
  availableServices: string[];
  /** Services shown when the chart first mounts. */
  initialSelected: string[];
  initialMetric?: Metric;
  initialMinutesBack?: number;
  /** Called after the user changes a setting by hand (e.g. to save it). */
  onSettingsChange?: (settings: { selected: string[]; metric: Metric; minutesBack: number }) => void;
}

const WIDTH = 760;
const HEIGHT = 260;
const PAD_LEFT = 46;
const PAD_RIGHT = 16;
const PAD_TOP = 14;
const PAD_BOTTOM = 26;
const REFRESH_MS = 15000;

const RANGES: { minutes: number; labelKey: "range15m" | "range1h" | "range6h" | "range24h" | "range7d" }[] = [
  { minutes: 15, labelKey: "range15m" },
  { minutes: 60, labelKey: "range1h" },
  { minutes: 360, labelKey: "range6h" },
  { minutes: 1440, labelKey: "range24h" },
  { minutes: 10080, labelKey: "range7d" },
];

type Metric = DashboardMetric;

/** Enough decimals that the axis labels stay distinct when the values are small. */
function formatTick(v: number, max: number): string {
  const decimals = max >= 20 ? 0 : max >= 2 ? 1 : 2;
  return v.toFixed(decimals);
}

function valueOf(metric: Metric, point: MetricPoint): number | null {
  if (metric === "errors") return point.errorsPerMinute ?? null;
  return metric === "cpu" ? point.cpuPercent : point.memoryMb;
}

/**
 * One Grafana-style chart for however many services are selected: a line per service, a metric
 * toggle (CPU% / memory), a time-range picker, and a hover readout. Polls every 15s.
 */
export default function MetricsChart({
  availableServices,
  initialSelected,
  initialMetric = "cpu",
  initialMinutesBack = 60,
  onSettingsChange,
}: MetricsChartProps) {
  const { t, lang } = useLanguage();
  const [selected, setSelectedState] = useState<string[]>(initialSelected);
  const [metric, setMetricState] = useState<Metric>(initialMetric);
  const [minutesBack, setMinutesBackState] = useState(initialMinutesBack);

  function setSelected(next: string[]) {
    setSelectedState(next);
    onSettingsChange?.({ selected: next, metric, minutesBack });
  }
  function setMetric(next: Metric) {
    setMetricState(next);
    onSettingsChange?.({ selected, metric: next, minutesBack });
  }
  function setMinutesBack(next: number) {
    setMinutesBackState(next);
    onSettingsChange?.({ selected, metric, minutesBack: next });
  }
  const [data, setData] = useState<ServiceMetrics[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hoverFrac, setHoverFrac] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  // Every service in the environment is loaded once per range; picking services only filters
  // what's already here, so it's instant (and the server's cached copy is shared by everyone).
  // CPU and memory come from the same heartbeat samples; error rates are counted from the logs.
  const source = metric === "errors" ? "errors" : "samples";
  const latestRequest = useRef(0);
  const load = useCallback(() => {
    const request = ++latestRequest.current;
    (source === "errors" ? fetchErrorRates(minutesBack) : fetchMetrics(undefined, minutesBack))
      .then((d) => {
        if (request !== latestRequest.current) return; // a newer range was picked meanwhile
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if (request === latestRequest.current) setError((e as Error).message);
      });
  }, [minutesBack, source]);

  useEffect(() => {
    load();
    const id = setInterval(load, REFRESH_MS);
    return () => clearInterval(id);
  }, [load]);

  const series = useMemo(() => {
    const all = data ?? [];
    return all
      // Color by position among all services, so a service keeps its color when others are toggled.
      .map((s, i) => ({
        serviceName: s.serviceName,
        color: chartColor(availableServices.includes(s.serviceName) ? availableServices.indexOf(s.serviceName) : i),
        points: s.samples
          .map((p) => ({ t: Date.parse(p.timestamp), v: valueOf(metric, p) }))
          .filter((p): p is { t: number; v: number } => p.v !== null && Number.isFinite(p.t)),
      }))
      .filter((s) => selected.length === 0 || selected.includes(s.serviceName));
  }, [data, metric, selected, availableServices]);

  const nonEmpty = series.filter((s) => s.points.length > 0);
  const allTimes = nonEmpty.flatMap((s) => s.points.map((p) => p.t));
  const allValues = nonEmpty.flatMap((s) => s.points.map((p) => p.v));
  const tMin = allTimes.length ? Math.min(...allTimes) : Date.now() - minutesBack * 60000;
  const tMax = allTimes.length ? Math.max(...allTimes) : Date.now();
  // The axis tops out at the highest point plus 10%, so low values still fill the chart.
  const vMax = allValues.length ? Math.max(...allValues) : 0;
  const yMax = vMax > 0 ? vMax * 1.1 : 1;

  const xOf = (t: number) => PAD_LEFT + ((t - tMin) / Math.max(tMax - tMin, 1)) * (WIDTH - PAD_LEFT - PAD_RIGHT);
  const yOf = (v: number) => HEIGHT - PAD_BOTTOM - (v / yMax) * (HEIGHT - PAD_TOP - PAD_BOTTOM);

  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * yMax);
  const locale = lang === "he" ? "he-IL" : "en-US";
  const xTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => tMin + f * (tMax - tMin));

  function handleMove(e: React.MouseEvent<SVGSVGElement>) {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect) return;
    const fracX = (e.clientX - rect.left) / rect.width;
    setHoverFrac(Math.min(1, Math.max(0, fracX)));
  }

  const hoverTime = hoverFrac !== null ? tMin + hoverFrac * (tMax - tMin) : null;
  const hoverEntries =
    hoverTime !== null
      ? nonEmpty
          .map((s) => {
            let nearest = s.points[0];
            let best = Infinity;
            for (const p of s.points) {
              const d = Math.abs(p.t - hoverTime);
              if (d < best) {
                best = d;
                nearest = p;
              }
            }
            return { serviceName: s.serviceName, color: s.color, point: nearest };
          })
          .filter((e) => e.point)
      : [];

  const unit = metric === "cpu" ? "%" : metric === "memory" ? "MB" : t.dashboard.metricsErrorsUnit;
  const rangeButtonClass = (active: boolean) =>
    `px-space-sm py-0.5 rounded-md font-code text-code-sm transition-all ${
      active ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
    }`;

  return (
    <div className="flex flex-col gap-space-sm">
      <div className="flex flex-wrap items-center justify-between gap-space-sm">
        <div className="flex items-center gap-space-sm">
          <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup">
            <button type="button" className={rangeButtonClass(metric === "cpu")} aria-pressed={metric === "cpu"} onClick={() => setMetric("cpu")}>
              {t.dashboard.metricsCpu}
            </button>
            <button type="button" className={rangeButtonClass(metric === "memory")} aria-pressed={metric === "memory"} onClick={() => setMetric("memory")}>
              {t.dashboard.metricsMemory}
            </button>
            <button type="button" className={rangeButtonClass(metric === "errors")} aria-pressed={metric === "errors"} onClick={() => setMetric("errors")}>
              {t.dashboard.metricsErrors}
            </button>
          </div>
          <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup">
            {RANGES.map((r) => (
              <button
                key={r.minutes}
                type="button"
                className={rangeButtonClass(minutesBack === r.minutes)}
                aria-pressed={minutesBack === r.minutes}
                onClick={() => setMinutesBack(r.minutes)}
              >
                {t.dashboard[r.labelKey]}
              </button>
            ))}
          </div>
        </div>
        <ServiceMultiSelect services={availableServices} selected={selected} onChange={setSelected} />
      </div>

      {error ? (
        <div className="flex items-center gap-space-sm font-body-md text-body-md text-error py-space-md">
          <Icon name="error" size={18} />
          {t.dashboard.chartError}: {error}
        </div>
      ) : data === null ? (
        <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
          <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
          {t.dashboard.chartLoading}
        </div>
      ) : nonEmpty.length === 0 ? (
        <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
          <Icon name="show_chart" size={28} />
          <span className="font-body-md text-body-md">{t.dashboard.chartEmpty}</span>
        </div>
      ) : (
        <div dir="ltr" className="relative overflow-x-auto">
          <svg
            ref={svgRef}
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            className="w-full h-auto"
            style={{ minWidth: 300 }}
            onMouseMove={handleMove}
            onMouseLeave={() => setHoverFrac(null)}
          >
            {yTicks.map((v, i) => (
              <g key={i}>
                <line
                  x1={PAD_LEFT}
                  x2={WIDTH - PAD_RIGHT}
                  y1={yOf(v)}
                  y2={yOf(v)}
                  className="stroke-outline-variant/25"
                  strokeWidth={1}
                />
                <text x={PAD_LEFT - 8} y={yOf(v) + 4} textAnchor="end" className="fill-on-surface-variant" fontSize={11}>
                  {formatTick(v, yMax)}
                </text>
              </g>
            ))}

            {xTicks.map((t0, i) => (
              <text key={i} x={xOf(t0)} y={HEIGHT - 6} textAnchor="middle" className="fill-on-surface-variant" fontSize={11}>
                {new Date(t0).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" })}
              </text>
            ))}

            {nonEmpty.map((s) => (
              <polyline
                key={s.serviceName}
                fill="none"
                stroke={s.color}
                strokeWidth={2}
                points={s.points.map((p) => `${xOf(p.t)},${yOf(p.v)}`).join(" ")}
              />
            ))}

            {hoverFrac !== null && (
              <line
                x1={PAD_LEFT + hoverFrac * (WIDTH - PAD_LEFT - PAD_RIGHT)}
                x2={PAD_LEFT + hoverFrac * (WIDTH - PAD_LEFT - PAD_RIGHT)}
                y1={PAD_TOP}
                y2={HEIGHT - PAD_BOTTOM}
                className="stroke-on-surface-variant/40"
                strokeWidth={1}
                strokeDasharray="3 3"
              />
            )}
          </svg>

          {hoverEntries.length > 0 && hoverTime !== null && (
            <div
              className="absolute top-2 bg-surface-container-highest text-on-surface rounded-xl shadow-xl p-space-sm font-code text-code-sm pointer-events-none"
              style={{
                left: `clamp(8px, calc(${(hoverFrac ?? 0) * 100}% + 10px), calc(100% - 180px))`,
                minWidth: 150,
              }}
            >
              <div className="text-on-surface-variant mb-1">
                {new Date(hoverTime).toLocaleTimeString(locale)}
              </div>
              {hoverEntries.map((e) => (
                <div key={e.serviceName} className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full shrink-0" style={{ background: e.color }} />
                  <span className="truncate">{e.serviceName}</span>
                  <span className="ms-auto text-secondary">
                    {e.point.v.toFixed(1)}
                    {unit}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {nonEmpty.length > 0 && (
        <div className="flex flex-wrap items-center gap-space-sm">
          {nonEmpty.map((s) => (
            <span key={s.serviceName} className="flex items-center gap-1.5 font-code text-code-sm text-on-surface-variant">
              <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ background: s.color }} />
              {s.serviceName}
              <span className="text-on-surface">
                {s.points[s.points.length - 1].v.toFixed(1)}
                {unit}
              </span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
