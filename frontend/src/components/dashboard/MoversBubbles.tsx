"use client";

import { useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { pct, topMovers } from "@/lib/mockData";

const MIN_W = Math.min(...topMovers.map((m) => m.weight));
const MAX_W = Math.max(...topMovers.map((m) => m.weight));
const size = (w: number) => 66 + ((w - MIN_W) / (MAX_W - MIN_W)) * 58; // 66–124px

/**
 * Movers as a floating bubble field — diameter encodes position weight, colour
 * encodes direction. Bubbles drift gently, lift + glow on hover, and reveal a
 * detail popover. Deliberately not a row-by-row trading-terminal table.
 */
export function MoversBubbles() {
  const reduce = useReducedMotion();
  const [active, setActive] = useState<string | null>(null);

  return (
    <div>
      <div className="flex items-baseline justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Movers · today</h3>
        <span className="text-[11px] text-text-secondary">bubble size = weight</span>
      </div>

      <div className="mt-3 flex flex-wrap items-center justify-center gap-3 py-2">
        {topMovers.map((m, i) => {
          const up = m.changePct >= 0;
          const color = up ? "#00FF88" : "#FF3B5C";
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
                animate={
                  reduce
                    ? { scale: 1, opacity: 1 }
                    : { scale: 1, opacity: 1, y: [0, -6, 0] }
                }
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
                  background: `radial-gradient(circle at 35% 30%, ${color}40, ${color}14 60%, transparent 75%)`,
                  border: `1.5px solid ${color}66`,
                  boxShadow: isActive ? `0 0 28px ${color}66, inset 0 0 18px ${color}22` : `0 0 14px ${color}22`,
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
                    className="absolute left-1/2 top-full z-10 mt-2 w-36 -translate-x-1/2 rounded-xl border border-white/10 bg-background/95 p-2.5 text-center shadow-xl backdrop-blur"
                  >
                    <p className="text-xs font-medium text-text-primary">{m.name}</p>
                    <p className="font-mono text-[11px] text-text-secondary tabular-nums">
                      ${m.price.toFixed(2)} · {m.weight}% of book
                    </p>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          );
        })}
      </div>
    </div>
  );
}
