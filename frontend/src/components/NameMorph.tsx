import { useEffect, useState } from "react";

/** What grows between the letters of MoSes: Mo[nitoring] Se[rvice]s. */
const FIRST_GROWS = "nitoring";
const SECOND_GROWS = "rvice";
/** Step 0 is "MoSes", step 1 "Mo Ses", and each step after that adds a letter to both words. */
const LAST_STEP = 1 + Math.max(FIRST_GROWS.length, SECOND_GROWS.length);

/** How long a step stays. The first three are meant to be read: MoSes, Mo Ses, Mon Sers. */
function holdMs(step: number): number {
  return [1300, 1100, 1100][step] ?? 230;
}

interface Props {
  /** Called once the full name is written. */
  onDone: () => void;
}

/**
 * The name unfolding: MoSes -> Mo Ses -> Mon Sers -> ... -> Monitoring Services. The letters of
 * MoSes stay bold (same color, only heavier), the others sprout between them behind two carets. Letters grow by font-size, so
 * the word widens smoothly at each glyph's real width without measuring anything.
 */
export default function NameMorph({ onDone }: Props) {
  const [step, setStep] = useState(() =>
    window.matchMedia("(prefers-reduced-motion: reduce)").matches ? LAST_STEP : 0,
  );
  const done = step >= LAST_STEP;

  useEffect(() => {
    if (done) {
      onDone();
      return;
    }
    const id = window.setTimeout(() => setStep(step + 1), holdMs(step));
    return () => window.clearTimeout(id);
  }, [step, done, onDone]);

  const grown = Math.max(0, step - 1);

  return (
    <div
      dir="ltr"
      role="img"
      aria-label="MoSes - Monitoring Services"
      data-step={step}
      className={`name-morph ${step === 0 ? "name-morph--whole" : ""} ${done ? "name-morph--done" : ""}`}
    >
      <span aria-hidden="true" className="name-morph__word">
        <span className="name-morph__kept">Mo</span>
        <Growing text={FIRST_GROWS} count={grown} caret={step > 0} />
        <span className="name-morph__space" />
        <span className="name-morph__kept">Se</span>
        <Growing text={SECOND_GROWS} count={grown} caret={step > 0} />
        <span className="name-morph__kept">s</span>
      </span>
    </div>
  );
}

function Growing({ text, count, caret }: { text: string; count: number; caret: boolean }) {
  const shown = Math.min(count, text.length);
  const writing = caret && shown < text.length;
  return (
    <>
      {[...text].map((letter, i) => (
        <span key={i} className={`name-morph__letter ${i < shown ? "name-morph__letter--in" : ""}`}>
          {letter}
          {/* Inside the newest letter, so the caret sits right where the next one will appear. */}
          {writing && i === shown - 1 && <span className="name-morph__caret" />}
        </span>
      ))}
    </>
  );
}
