import { useState } from "react";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { LOG_RETENTION_DAYS, RANGE_PRESETS, toLocalInput, validateCustom, type TimeRange } from "../timeRange";

interface Props {
  value: TimeRange;
  onChange: (range: TimeRange) => void;
}

/**
 * Quick ranges (15 min ... 14 days) plus "custom": from a date/time, to a date/time or until now.
 * Times are entered in the browser's local time and sent to the server as UTC.
 */
export default function TimeRangePicker({ value, onChange }: Props) {
  const { t, lang } = useLanguage();
  const s = t.search;
  const custom = !("minutes" in value);
  const [open, setOpen] = useState(custom);
  const [from, setFrom] = useState(custom ? toLocalInput(value.from) : "");
  const [to, setTo] = useState(custom ? toLocalInput(value.to) : "");
  const [problem, setProblem] = useState<string | null>(null);
  const locale = lang === "he" ? "he-IL" : "en-US";

  const presetLabel = (minutes: number) =>
    minutes < 60 ? `${minutes}${s.unitMinutes}` : minutes < 1440 ? `${minutes / 60}${s.unitHours}` : `${minutes / 1440}${s.unitDays}`;

  function apply() {
    const fromDate = from ? new Date(from) : null;
    const toDate = to ? new Date(to) : null;
    const issue = validateCustom(fromDate, toDate);
    if (issue) {
      setProblem(issue === "missing" ? s.rangeMissing : issue === "order" ? s.rangeOrder : s.rangeRetention.replace("{days}", String(LOG_RETENTION_DAYS)));
      return;
    }
    setProblem(null);
    onChange(toDate ? { from: fromDate!.toISOString(), to: toDate.toISOString() } : { from: fromDate!.toISOString() });
  }

  const button = (active: boolean) =>
    `px-space-sm py-0.5 rounded-md font-code text-code-sm transition-all ${
      active ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
    }`;
  const now = new Date();
  const earliest = new Date(now.getTime() - LOG_RETENTION_DAYS * 24 * 3_600_000);
  const fmt = (iso: string) => new Date(iso).toLocaleString(locale, { dateStyle: "short", timeStyle: "short" });

  return (
    <div className="flex flex-col gap-space-xs">
      <div className="flex flex-wrap items-center gap-space-sm">
        <span className="flex items-center gap-1 font-label-sm text-label-sm text-on-surface-variant">
          <Icon name="schedule" size={16} />
          {s.rangeLabel}
        </span>
        <div className="flex items-center bg-surface-container-high rounded-lg p-0.5" role="radiogroup" aria-label={s.rangeLabel}>
          {RANGE_PRESETS.map((m) => (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={!custom && value.minutes === m}
              className={button(!custom && value.minutes === m)}
              onClick={() => {
                setOpen(false);
                setProblem(null);
                onChange({ minutes: m });
              }}
            >
              {presetLabel(m)}
            </button>
          ))}
          <button
            type="button"
            role="radio"
            aria-checked={custom || open}
            className={button(custom || open)}
            onClick={() => setOpen(true)}
          >
            {s.rangeCustom}
          </button>
        </div>
        {custom && (
          <span dir="auto" className="font-body-sm text-body-sm text-on-surface">
            {value.to ? `${fmt(value.from)} – ${fmt(value.to)}` : `${fmt(value.from)} – ${s.rangeNow}`}
          </span>
        )}
      </div>
      {open && (
        <div className="flex flex-wrap items-end gap-space-sm">
          <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
            {s.rangeFrom}
            <input
              type="datetime-local"
              className="bg-surface-container-high rounded-lg px-space-sm py-1 font-code text-code-sm text-on-surface"
              value={from}
              min={toLocalInput(earliest.toISOString())}
              max={toLocalInput(now.toISOString())}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 font-label-sm text-label-sm text-on-surface-variant">
            {s.rangeTo}
            <input
              type="datetime-local"
              className="bg-surface-container-high rounded-lg px-space-sm py-1 font-code text-code-sm text-on-surface"
              value={to}
              min={from || toLocalInput(earliest.toISOString())}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <span className="font-body-sm text-body-sm text-on-surface-variant pb-1">{s.rangeToHint}</span>
          <button type="button" className="px-space-md py-1 btn btn-primary" onClick={apply}>
            {s.rangeApply}
          </button>
        </div>
      )}
      {problem && <span className="font-body-sm text-body-sm text-error">{problem}</span>}
    </div>
  );
}
