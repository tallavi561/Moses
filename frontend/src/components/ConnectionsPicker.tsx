import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

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
    <div className="flex flex-col gap-space-xs">
      <div className="font-label-md text-label-md text-on-surface-variant uppercase">{t.flow.callsLabel}</div>
      <p className="font-body-sm text-body-sm text-on-surface-variant">{t.flow.callsHint}</p>

      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selected.map((service) => (
            <span
              key={service}
              className="inline-flex items-center gap-1 pl-space-sm pr-1 py-1 rounded-full bg-secondary-container/20 text-secondary font-code text-code-sm"
            >
              {service}
              <button
                type="button"
                className="w-4 h-4 rounded-full flex items-center justify-center hover:bg-secondary/20"
                onClick={() => remove(service)}
                aria-label={t.flow.removeConnection.replace("{service}", service)}
              >
                <Icon name="close" size={12} />
              </button>
            </span>
          ))}
        </div>
      )}

      {remaining.length > 0 ? (
        <select
          className="bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-1.5 rounded-lg focus:outline-none focus:ring-1 focus:ring-secondary cursor-pointer"
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
        <p className="font-body-sm text-body-sm text-on-surface-variant">{t.flow.noMoreServicesToConnect}</p>
      ) : null}
    </div>
  );
}
