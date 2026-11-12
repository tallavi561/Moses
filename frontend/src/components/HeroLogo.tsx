import { useEffect, useRef, useState } from "react";
import introVideo from "../assets/hero-logo.mp4";
import loopVideo from "../assets/hero-loop.mp4";
import firstFrame from "../assets/hero-logo-first.webp";
import lastFrame from "../assets/hero-logo-last.webp";

/** The intro plays once per page load; screens opened afterwards go straight to the idle loop. */
let hasPlayedIntro = false;

type Phase = "intro" | "loop" | "still";

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

interface HeroLogoProps {
  /** Smaller variant for screens that also need room for content below (project picker). */
  compact?: boolean;
}

/**
 * Large, horizontally centered logo in two stages:
 *   1. the intro video plays once (not looped), ending on its last frame;
 *   2. the idle loop takes over. Its first frame IS that last frame (see scripts/make-hero-loop.py),
 *      so the hand-off is invisible, and it repeats forever with very subtle motion.
 * Both videos sit on top of each other and only one is visible at a time (they are blended with
 * the page, so showing both would add their brightness). Reduced motion, a blocked autoplay or a
 * broken video fall back to the last frame as a still image.
 */
export default function HeroLogo({ compact = false }: HeroLogoProps) {
  const [phase, setPhase] = useState<Phase>(() =>
    prefersReducedMotion() ? "still" : hasPlayedIntro ? "loop" : "intro"
  );
  const [loopBroken, setLoopBroken] = useState(false);
  const introRef = useRef<HTMLVideoElement>(null);
  const loopRef = useRef<HTMLVideoElement>(null);

  const shown: Phase = phase === "loop" && loopBroken ? "still" : phase;

  // Start whichever video is current once the tab is visible (browsers won't start one in a
  // background tab).
  useEffect(() => {
    if (phase === "still") return;
    const video = phase === "intro" ? introRef.current : loopRef.current;
    if (phase === "intro") hasPlayedIntro = true;
    const start = () => video?.play().catch(() => setPhase("still"));
    if (!document.hidden) {
      start();
      return;
    }
    const onVisible = () => {
      if (document.hidden) return;
      document.removeEventListener("visibilitychange", onVisible);
      start();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [phase]);

  const hidden = (visible: boolean) => (visible ? undefined : "hero-logo__hidden");

  return (
    <div className={`hero-logo ${compact ? "hero-logo--compact" : ""}`} role="img" aria-label="MoSes Monitor">
      {phase !== "still" && (
        <>
          {phase === "intro" && (
            <video
              ref={introRef}
              src={introVideo}
              poster={firstFrame}
              muted
              playsInline
              preload="auto"
              disablePictureInPicture
              tabIndex={-1}
              onEnded={() => setPhase("loop")}
              onError={() => setPhase(loopBroken ? "still" : "loop")}
            />
          )}
          {/* Mounted from the start so it is decoded and waiting at frame 1 when the intro ends. */}
          <video
            ref={loopRef}
            className={hidden(shown === "loop")}
            src={loopVideo}
            poster={lastFrame}
            muted
            loop
            playsInline
            preload="auto"
            disablePictureInPicture
            tabIndex={-1}
            onError={() => setLoopBroken(true)}
          />
        </>
      )}
      {shown === "still" && <img src={lastFrame} alt="" draggable={false} />}
    </div>
  );
}
