"use client";

import { motion, useReducedMotion } from "motion/react";

const BENEFITS = [
  { icon: "strategy", text: "Learns your investment strategy from what you actually hold" },
  { icon: "analysis", text: "Runs every agent's analysis on your real stocks, not a demo list" },
  { icon: "target", text: "Tailors buy/sell guidance and alerts to your own portfolio" },
] as const;

function BenefitIcon({ icon }: { icon: (typeof BENEFITS)[number]["icon"] }) {
  const common = { viewBox: "0 0 16 16", width: 15, height: 15, fill: "none", stroke: "currentColor", strokeWidth: 1.4 } as const;
  if (icon === "strategy") {
    return (
      <svg {...common}>
        <path d="M2 13 6 7l3 3 5-6" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M11 4h3v3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }
  if (icon === "analysis") {
    return (
      <svg {...common}>
        <circle cx="7" cy="7" r="5" />
        <path d="M11 11 14 14" strokeLinecap="round" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <circle cx="8" cy="8" r="6" />
      <circle cx="8" cy="8" r="2.5" />
    </svg>
  );
}

/**
 * Shown on the Portfolio page instead of the (otherwise empty) value/overview/holdings grid — the
 * page's very first real moment, so it argues FOR uploading rather than just stating "no holdings
 * yet": the whole point of Argus is personalized analysis, which can't start until it knows what you
 * actually hold. {@link onImport} opens the same upload dialog as the header action.
 */
export function EmptyPortfolio({ onImport }: { onImport: () => void }) {
  const reduce = useReducedMotion();

  return (
    <motion.div
      initial={reduce ? false : { opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 120, damping: 18 }}
      className="glass relative flex flex-col items-center gap-6 overflow-hidden rounded-2xl px-6 py-14 text-center"
    >
      <motion.div
        aria-hidden="true"
        className="flex h-16 w-16 items-center justify-center rounded-2xl border border-dashed border-accent/50 text-accent"
        animate={reduce ? undefined : { y: [0, -8, 0] }}
        transition={reduce ? undefined : { duration: 2.6, repeat: Infinity, ease: "easeInOut" }}
      >
        <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M12 16V4M7 9l5-5 5 5" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </motion.div>

      <div className="flex flex-col gap-2">
        <h2 className="font-display text-xl font-bold text-text-primary">Upload your portfolio</h2>
        <p className="mx-auto max-w-sm text-sm leading-relaxed text-text-secondary">
          So Argus can learn your investment strategy, study the stocks you actually hold, and run its
          full analysis on them — for much more personalized results than generic advice.
        </p>
      </div>

      <button
        type="button"
        onClick={onImport}
        className="flex min-h-[44px] cursor-pointer items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-sm font-medium text-background transition-opacity hover:opacity-90"
      >
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M8 2v8M4.5 6.5 8 10l3.5-3.5M2 12.5v1a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-1" />
        </svg>
        Import your first statement
      </button>

      <ul className="flex flex-col gap-2.5 pt-2 text-left">
        {BENEFITS.map((b) => (
          <li key={b.icon} className="flex items-center gap-2.5 text-xs text-text-secondary">
            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-accent/10 text-accent">
              <BenefitIcon icon={b.icon} />
            </span>
            {b.text}
          </li>
        ))}
      </ul>
    </motion.div>
  );
}
