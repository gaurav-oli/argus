"use client";

import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";

/** Quantise progress into N hard steps — the stuttering line-by-line paint of a CRT. */
const crtSteps = (n: number) => (t: number) => Math.min(1, Math.ceil(t * n) / n);

/**
 * Terminal Noir pane — amber-framed surface (styled by `.terminal-theme .glass`). Each pane "boots"
 * in: a stepped top-to-bottom wipe like a CRT painting rows, cascading by `index`. No hover lift —
 * a terminal pane doesn't float; hover lights the frame instead (CSS).
 */
export function MotionCard({
  className,
  children,
  interactive = true,
  index = 0,
  entrance = "fade",
  reveal = "mount",
  ...rest
}: React.ComponentProps<typeof motion.div> & {
  interactive?: boolean;
  index?: number;
  /** "none" renders visible immediately — use on pages with heavy persistent animation. */
  entrance?: "fade" | "none";
  /** "viewport" replays the entrance each time the card scrolls into view instead of once on mount. */
  reveal?: "mount" | "viewport";
}) {
  const reduce = useReducedMotion();
  const skip = reduce || entrance === "none";
  const shown = { opacity: 1, clipPath: "inset(0% 0% 0% 0%)" };
  const revealProps =
    reveal === "viewport"
      ? { whileInView: shown, viewport: { once: true, margin: "-60px" } }
      : { animate: shown };
  return (
    <motion.div
      initial={skip ? false : { opacity: 0, clipPath: "inset(0% 0% 100% 0%)" }}
      {...revealProps}
      transition={skip ? { duration: 0 } : { delay: index * 0.08, duration: 0.55, ease: crtSteps(12) }}
      className={cn(
        "group relative overflow-hidden p-5 glass",
        interactive && "glass-interactive",
        className,
      )}
      {...rest}
    >
      {children}
    </motion.div>
  );
}
