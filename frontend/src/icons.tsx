/**
 * Thin wrapper around the Material Symbols Outlined font (loaded in
 * index.html). Pass any Material Symbols icon name - swap the name here or
 * at the call site and every usage updates.
 */
interface IconProps {
  name: string;
  className?: string;
  size?: number;
  title?: string;
}

export function Icon({ name, className = "", size = 20, title }: IconProps) {
  return (
    <span
      className={`material-symbols-outlined leading-none ${className}`}
      style={{ fontSize: size }}
      aria-hidden={title ? undefined : true}
      title={title}
    >
      {name}
    </span>
  );
}
