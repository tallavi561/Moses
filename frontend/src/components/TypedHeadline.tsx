import { useEffect, useState } from "react";

const START_MS = 600;
const TYPE_MS = 55;
const ERASE_MS = 22;
const HOLD_MS = 1700;

interface Props {
  /** Typed and erased one after the other; the last one stays. */
  lines: string[];
  /** Nothing is typed (and no caret shows) until this is true. */
  play: boolean;
  className?: string;
}

/**
 * A headline that types itself with a blinking caret. It runs once and rests on its last line, so
 * the page goes quiet while someone fills in the form. The tallest line reserves the height up
 * front, screen readers get the last line only, and reduced motion shows it without typing.
 */
export default function TypedHeadline({ lines, play, className = "" }: Props) {
  const [shown, setShown] = useState("");
  const script = lines.join("\n");

  useEffect(() => {
    if (!play) return;
    const steps = script.split("\n");
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setShown(steps[steps.length - 1]);
      return;
    }
    let line = 0;
    let length = 0;
    let erasing = false;
    let timer: number;
    setShown("");

    function tick() {
      length += erasing ? -1 : 1;
      setShown(steps[line].slice(0, length));
      let delay = erasing ? ERASE_MS : TYPE_MS;
      if (erasing && length === 0) {
        erasing = false;
        line += 1;
      } else if (!erasing && length === steps[line].length) {
        if (line === steps.length - 1) return;
        erasing = true;
        delay = HOLD_MS;
      }
      timer = window.setTimeout(tick, delay);
    }

    timer = window.setTimeout(tick, START_MS);
    return () => window.clearTimeout(timer);
  }, [script, play]);

  return (
    <h2 className={`grid text-balance ${className}`} aria-label={lines[lines.length - 1]}>
      {lines.map((line) => (
        <span key={line} aria-hidden="true" className="invisible [grid-area:1/1]">
          {line}
        </span>
      ))}
      <span aria-hidden="true" data-typed className="whitespace-pre-wrap [grid-area:1/1]">
        {shown}
        {play && <span className="typed-caret" />}
      </span>
    </h2>
  );
}
