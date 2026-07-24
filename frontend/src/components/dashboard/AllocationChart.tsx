"use client";

import { useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import { getPortfolioValue, type PortfolioSnapshot } from "@/lib/apiClient";

const CATS = [
  "var(--cat-1)",
  "var(--cat-2)",
  "var(--cat-3)",
  "var(--cat-4)",
  "var(--cat-5)",
  "var(--cat-6)",
];

/** Allocation by holding weight (Story 3.4) — a single stacked rule + inline labels, driven by real positions. */
export function AllocationChart() {
  const reduce = useReducedMotion();
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);

  useEffect(() => {
    let alive = true;
    getPortfolioValue().then((s) => alive && setSnap(s)).catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  // Positions are per-lot (a ticker can appear once per account/holding), so group by ticker and
  // sum weight before rendering — otherwise a stock held across several accounts shows up as
  // several duplicate rows instead of one combined figure.
  const weightByTicker = new Map<string, number>();
  for (const p of snap?.positions ?? []) {
    if (p.weightPercent == null) continue;
    weightByTicker.set(p.ticker, (weightByTicker.get(p.ticker) ?? 0) + p.weightPercent);
  }
  const allocation = [...weightByTicker.entries()]
    .map(([name, value], i) => ({ name, value: Math.round(value), color: CATS[i % CATS.length] }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 6);

  return (
    <div className="flex h-full flex-col">
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Allocation</h3>

      {allocation.length === 0 ? (
        <div className="flex flex-1 items-center justify-center text-center text-xs text-text-secondary">
          {snap === null ? "Loading…" : "No holdings yet — import a statement to see your mix."}
        </div>
      ) : (
        <div className="mt-4 flex flex-1 flex-col justify-center gap-4">
          <div className="flex h-2 w-full overflow-hidden bg-[var(--hairline)]">
            {allocation.map((s, i) => (
              <motion.div
                key={s.name}
                initial={reduce ? false : { width: 0 }}
                animate={{ width: `${s.value}%` }}
                transition={reduce ? { duration: 0 } : { delay: 0.1 + i * 0.06, duration: 0.6, ease: "easeOut" }}
                style={{ backgroundColor: s.color }}
              />
            ))}
          </div>

          <ul className="flex flex-wrap gap-x-5 gap-y-2">
            {allocation.map((s) => (
              <li key={s.name} className="flex items-center gap-1.5 text-xs">
                <span className="h-2 w-2" style={{ backgroundColor: s.color }} />
                <span className="text-text-primary">{s.name}</span>
                <span className="font-serif-editorial text-text-secondary">{s.value}%</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
