import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "../icons";

interface InfoHintProps {
  title: string;
  children: ReactNode;
}

/** A small "i" button that opens a short usage-docs popover; closes on outside click or Escape. */
export default function InfoHint({ title, children }: InfoHintProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative inline-block">
      <button
        type="button"
        aria-label={title}
        aria-expanded={open}
        title={title}
        className={`flex items-center justify-center w-6 h-6 rounded-full transition-colors ${
          open ? "bg-secondary-container/30 text-secondary" : "text-on-surface-variant hover:bg-surface-container-high hover:text-secondary"
        }`}
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="info" size={18} />
      </button>
      {open && (
        <div
          role="dialog"
          className="absolute z-30 top-full mt-space-xs start-0 w-80 max-w-[85vw] bg-surface-container-highest rounded-xl shadow-xl p-space-md font-body-sm text-body-sm text-on-surface"
        >
          <h3 className="font-headline text-headline-sm text-on-surface mb-space-xs">{title}</h3>
          {children}
        </div>
      )}
    </div>
  );
}
