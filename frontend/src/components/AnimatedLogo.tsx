import { useEffect, useState } from "react";
import sprite from "../assets/logo-sprite.png";

/**
 * The animation plays once per page load, in whichever logo mounts first; logos that mount later
 * (navigating between screens) show the finished emblem instead of replaying it.
 */
let hasPlayed = false;

interface AnimatedLogoProps {
  /** Width and height in px. */
  size?: number;
  className?: string;
}

/**
 * The project emblem. The art is black on a transparent background, so it sits on a light tile
 * to stay visible in the dark theme (and looks the same in the light one).
 */
export default function AnimatedLogo({ size = 40, className = "" }: AnimatedLogoProps) {
  const [play] = useState(() => !hasPlayed);
  const [ready, setReady] = useState(false);

  // Start only once the sprite is decoded, so the first frames aren't skipped while it loads.
  useEffect(() => {
    let cancelled = false;
    const img = new Image();
    img.onload = () => {
      if (cancelled) return;
      hasPlayed = true;
      setReady(true);
    };
    img.onerror = () => {
      if (!cancelled) setReady(true);
    };
    img.src = sprite;
    return () => {
      cancelled = true;
    };
  }, []);

  const inner = Math.round(size * 0.84);
  return (
    <div
      className={`shrink-0 flex items-center justify-center bg-[#f4f4f7] shadow-md ${className}`}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.22) }}
      role="img"
      aria-label="MoSes Monitor"
    >
      {ready && (
        <span
          className={`logo-sprite block ${play ? "logo-sprite--play" : ""}`}
          style={{ width: inner, height: inner, backgroundImage: `url(${sprite})` }}
        />
      )}
    </div>
  );
}
