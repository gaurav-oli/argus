"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { getPortfolioValue, type PortfolioSnapshot } from "@/lib/apiClient";
import { pct } from "@/lib/format";

type Mover = { symbol: string; name: string; changePct: number; weight: number; price: number };

/**
 * Movers as a floating bubble field (driven by real positions, /api/portfolio/value) — diameter
 * encodes position weight, colour encodes the day's (or total) move. Empty until holdings exist.
 */
export function MoversBubbles() {
  const reduce = useReducedMotion();
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);
  const [active, setActive] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getPortfolioValue().then((s) => alive && setSnap(s)).catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  const movers: Mover[] = (snap?.positions ?? [])
    .map((p) => ({
      symbol: p.ticker,
      name: p.companyName ?? p.ticker,
      changePct: p.dayPnlPercent ?? p.totalPnlPercent ?? 0,
      weight: p.weightPercent ?? 0,
      price: p.price ?? 0,
    }))
    .sort((a, b) => Math.abs(b.changePct) - Math.abs(a.changePct))
    .slice(0, 6);

  const minW = Math.min(...movers.map((m) => m.weight));
  const maxW = Math.max(...movers.map((m) => m.weight));
  const size = (w: number) => (maxW === minW ? 92 : 66 + ((w - minW) / (maxW - minW)) * 58);

  return (
    <div>
      <div className="flex items-baseline justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Movers · today</h3>
        <span className="text-[11px] text-text-secondary">bubble size = weight</span>
      </div>

      {movers.length === 0 ? (
        <div className="flex h-32 items-center justify-center text-center text-xs text-text-secondary">
          {snap === null ? "Loading…" : "No holdings yet — import a statement to see movers."}
        </div>
      ) : (
        <div className="mt-3 flex flex-wrap items-center justify-center gap-3 py-2">
          {movers.map((m, i) => {
            const up = m.changePct >= 0;
            const color = up ? "var(--chart-gains)" : "var(--chart-losses)";
            const d = size(m.weight);
            const isActive = active === m.symbol;
            return (
              <div key={m.symbol} className="relative" style={{ width: d, height: d }}>
                <motion.button
                  onHoverStart={() => setActive(m.symbol)}
                  onHoverEnd={() => setActive((s) => (s === m.symbol ? null : s))}
                  onFocus={() => setActive(m.symbol)}
                  onBlur={() => setActive((s) => (s === m.symbol ? null : s))}
                  initial={reduce ? false : { scale: 0, opacity: 0 }}
                  animate={reduce ? { scale: 1, opacity: 1 } : { scale: 1, opacity: 1, y: [0, -6, 0] }}
                  transition={
                    reduce
                      ? { duration: 0 }
                      : {
                          scale: { delay: i * 0.08, type: "spring", stiffness: 200, damping: 14 },
                          opacity: { delay: i * 0.08 },
                          y: { repeat: Infinity, duration: 3 + i * 0.35, ease: "easeInOut", delay: i * 0.2 },
                        }
                  }
                  whileHover={reduce ? undefined : { scale: 1.12 }}
                  className="flex h-full w-full flex-col items-center justify-center rounded-full text-center"
                  style={{
                    background: `radial-gradient(circle at 35% 30%, color-mix(in srgb, ${color} 25%, transparent), color-mix(in srgb, ${color} 8%, transparent) 60%, transparent 75%)`,
                    border: `1.5px solid color-mix(in srgb, ${color} 40%, transparent)`,
                    boxShadow: isActive
                      ? `0 0 28px color-mix(in srgb, ${color} 40%, transparent), inset 0 0 18px color-mix(in srgb, ${color} 13%, transparent)`
                      : `0 0 14px color-mix(in srgb, ${color} 13%, transparent)`,
                  }}
                >
                  <span className="font-mono text-xs font-bold text-text-primary">{m.symbol}</span>
                  <span className="font-mono text-[11px] font-semibold tabular-nums" style={{ color }}>
                    {pct(m.changePct)}
                  </span>
                </motion.button>

                <AnimatePresence>
                  {isActive && (
                    <motion.div
                      initial={{ opacity: 0, y: 6, scale: 0.9 }}
                      animate={{ opacity: 1, y: 0, scale: 1 }}
                      exit={{ opacity: 0, y: 6, scale: 0.9 }}
                      transition={{ duration: 0.16 }}
                      className="absolute left-1/2 top-full z-10 mt-2 w-36 -translate-x-1/2 rounded-xl border border-border bg-background/95 p-2.5 text-center shadow-xl backdrop-blur"
                    >
                      <p className="text-xs font-medium text-text-primary">{m.name}</p>
                      <p className="font-mono text-[11px] text-text-secondary tabular-nums">
                        ${m.price.toFixed(2)} · {m.weight.toFixed(0)}% of book
                      </p>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
