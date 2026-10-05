"use client";

import { useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";

/**
 * Terminal Noir typewriter — reveals `text` a few characters per frame-tick with a blinking block
 * caret, restarting whenever `text` changes. Screen readers get the full string at once (the typed
 * span is aria-hidden), and reduced-motion users just see the full text.
 */
export function TypedText({
  text,
  charsPerTick = 2,
  tickMs = 24,
  caret = true,
  className,
}: {
  text: string;
  charsPerTick?: number;
  tickMs?: number;
  /** Keep the blinking caret after typing finishes. */
  caret?: boolean;
  className?: string;
}) {
  const reduce = useReducedMotion();
  // Progress is stored WITH the text it belongs to, so a new `text` reads as 0 typed characters
  // immediately — no reset-in-effect needed.
  const [progress, setProgress] = useState({ text, n: 0 });
  const shown = progress.text === text ? progress.n : 0;

  useEffect(() => {
    if (reduce) return;
    const id = setInterval(() => {
      setProgress((p) => {
        const n = p.text === text ? p.n : 0;
        if (n >= text.length) {
          clearInterval(id);
          return p.text === text ? p : { text, n };
        }
        return { text, n: Math.min(text.length, n + charsPerTick) };
      });
    }, tickMs);
    return () => clearInterval(id);
  }, [text, charsPerTick, tickMs, reduce]);

  const visible = reduce ? text : text.slice(0, shown);
  const typing = !reduce && shown < text.length;

  return (
    <span className={className}>
      <span className="sr-only">{text}</span>
      <span aria-hidden>
        {visible}
        {(caret || typing) && <span className="term-caret" />}
      </span>
    </span>
  );
}
