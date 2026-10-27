import { useLanguage } from "../i18n/LanguageContext";

interface ConnectionsPickerProps {
  selected: string[];
  available: string[];
  onChange: (next: string[]) => void;
}

/**
 * Editable set of "connects to" targets: already-picked services show as
 * removable chips, and a dropdown of the remaining services adds one at a
 * time. Deliberately not a checkbox grid - picking should feel like
 * building a list of connection objects, not ticking boxes.
 */
export default function ConnectionsPicker({ selected, available, onChange }: ConnectionsPickerProps) {
  const { t } = useLanguage();
  const remaining = available.filter((service) => !selected.includes(service));

  function add(service: string) {
    if (!service || selected.includes(service)) return;
    onChange([...selected, service]);
  }

  function remove(service: string) {
    onChange(selected.filter((s) => s !== service));
  }

  return (
    <div className="calls-picker">
      <div className="calls-label">{t.flow.callsLabel}</div>
      <p className="hint">{t.flow.callsHint}</p>

      {selected.length > 0 && (
        <div className="connection-chips">
          {selected.map((service) => (
            <span key={service} className="connection-chip">
              {service}
              <button
                type="button"
                className="connection-chip-remove"
                onClick={() => remove(service)}
                aria-label={t.flow.removeConnection.replace("{service}", service)}
              >
                &times;
              </button>
            </span>
          ))}
        </div>
      )}

      {remaining.length > 0 ? (
        <select
          className="status-select connection-add-select"
          value=""
          onChange={(e) => add(e.target.value)}
        >
          <option value="" disabled>
            {t.flow.addConnectionPlaceholder}
          </option>
          {remaining.map((service) => (
            <option key={service} value={service}>
              {service}
            </option>
          ))}
        </select>
      ) : available.length > 0 ? (
        <p className="hint">{t.flow.noMoreServicesToConnect}</p>
      ) : null}
    </div>
  );
}
