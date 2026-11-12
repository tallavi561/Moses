import { useEffect, useRef, useState } from "react";
import video from "../assets/hero-logo.mp4";
import firstFrame from "../assets/hero-logo-first.webp";
import lastFrame from "../assets/hero-logo-last.webp";

/** The intro plays once per page load; screens opened afterwards show the finished frame. */
let hasPlayed = false;

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

interface HeroLogoProps {
  /** Smaller variant for screens that also need room for content below (project picker). */
  compact?: boolean;
}

/**
 * Large, horizontally centered intro logo. The video is not looped, so when it ends the browser
 * simply holds the last frame. If it can't autoplay (blocked, unsupported) or the user prefers
 * reduced motion, the last frame is shown as a still instead.
 */
export default function HeroLogo({ compact = false }: HeroLogoProps) {
  const [play] = useState(() => !hasPlayed && !prefersReducedMotion());
  const [stillOnly, setStillOnly] = useState(!play);
  const ref = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    if (!play) return;
    hasPlayed = true;
    const start = () => ref.current?.play().catch(() => setStillOnly(true));
    if (!document.hidden) {
      start();
      return;
    }
    // Opened in a background tab: browsers won't start it, so begin once the tab is shown.
    const onVisible = () => {
      if (document.hidden) return;
      document.removeEventListener("visibilitychange", onVisible);
      start();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [play]);

  return (
    <div className={`hero-logo ${compact ? "hero-logo--compact" : ""}`} role="img" aria-label="MoSes Monitor">
      {stillOnly ? (
        <img src={lastFrame} alt="" draggable={false} />
      ) : (
        <video
          ref={ref}
          src={video}
          poster={firstFrame}
          muted
          playsInline
          autoPlay
          preload="auto"
          disablePictureInPicture
          tabIndex={-1}
          onError={() => setStillOnly(true)}
        />
      )}
    </div>
  );
}
