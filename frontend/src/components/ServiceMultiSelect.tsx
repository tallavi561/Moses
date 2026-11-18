import { useRef, useState } from "react";
import { useLanguage, pluralize } from "../i18n/LanguageContext";
import { useOnClickOutside } from "../layout/useOnClickOutside";
import { Icon } from "../icons";

interface ServiceMultiSelectProps {
  services: string[];
  selected: string[];
  onChange: (selected: string[]) => void;
}

/**
 * "All services" or a comma-free count when some are picked, a type-to-filter search box (so a
 * long service list stays usable without scrolling through all of it), and a checkbox per service
 * so several can be watched in parallel.
 */
export default function ServiceMultiSelect({ services, selected, onChange }: ServiceMultiSelectProps) {
  const { t } = useLanguage();
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  useOnClickOutside(ref, () => setOpen(false));

  function toggle(name: string) {
    onChange(selected.includes(name) ? selected.filter((s) => s !== name) : [...selected, name]);
  }

  function selectAll() {
    onChange([]);
    setFilter("");
  }

  const filtered = services.filter((s) => s.toLowerCase().includes(filter.trim().toLowerCase()));

  const label =
    selected.length === 0
      ? t.search.allServices
      : pluralize(t.search.servicesSelected_one, t.search.servicesSelected_other, selected.length);

  return (
    <div className="relative shrink-0" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={t.search.serviceFilter}
        aria-expanded={open}
        className="flex items-center gap-1.5 max-w-[12rem] bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-sm py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary"
      >
        <span className="truncate">{label}</span>
        <Icon
          name="expand_more"
          size={16}
          className={`text-on-surface-variant shrink-0 transition-transform ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <div className="absolute right-0 top-[calc(100%+8px)] w-64 dropdown-panel">
          <div className="relative">
            <Icon
              name="search"
              size={16}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-on-surface-variant"
            />
            <input
              type="text"
              autoFocus
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder={t.search.servicesSearchPlaceholder}
              className="w-full bg-surface-container-highest text-on-surface font-body-sm text-body-sm pl-8 pr-2 py-1.5 rounded-lg focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
            />
          </div>

          <button
            type="button"
            onClick={selectAll}
            className="w-full flex items-center gap-2 text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high text-on-surface font-body-md text-body-md transition-colors"
          >
            <Icon
              name={selected.length === 0 ? "radio_button_checked" : "radio_button_unchecked"}
              size={16}
              className={selected.length === 0 ? "text-secondary" : "text-on-surface-variant"}
            />
            {t.search.allServices}
          </button>

          <div className="h-px bg-outline-variant my-1" />

          <div className="max-h-56 overflow-y-auto">
            {filtered.length === 0 ? (
              <div className="px-space-sm py-space-xs font-body-sm text-body-sm text-on-surface-variant">
                {t.search.noServicesFound}
              </div>
            ) : (
              filtered.map((name) => {
                const checked = selected.includes(name);
                return (
                  <button
                    key={name}
                    type="button"
                    onClick={() => toggle(name)}
                    className="w-full flex items-center gap-2 text-left px-space-sm py-space-xs rounded-xl hover:bg-surface-container-high text-on-surface font-body-md text-body-md transition-colors"
                  >
                    <Icon
                      name={checked ? "check_box" : "check_box_outline_blank"}
                      size={16}
                      className={checked ? "text-secondary" : "text-on-surface-variant"}
                    />
                    <span className="truncate">{name}</span>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}
