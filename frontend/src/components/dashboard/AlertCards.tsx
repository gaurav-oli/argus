"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";

import { getLiveAlerts, type LiveAlert } from "@/lib/apiClient";
import { relTime } from "@/lib/time";

const tier = {
  critical: { color: "var(--color-losses)", label: "Critical", glyph: "!" },
  warning: { color: "var(--color-warning)", label: "Watch", glyph: "▲" },
  info: { color: "var(--color-accent)", label: "Info", glyph: "i" },
} as const;

/**
 * Live Alerts feed (Epic 9) — wired to /api/alerts/live, which composes real agent output:
 * Stranger Danger warnings, upcoming calendar events, and fresh recommendations. Each card carries
 * a tier accent + icon, lifts and washes on hover, and animates in with a stagger.
 */
export function AlertCards() {
  const reduce = useReducedMotion();
  const [items, setItems] = useState<LiveAlert[] | null>(null);

  useEffect(() => {
    let active = true;
    getLiveAlerts()
      .then((v) => active && setItems(v))
      .catch(() => active && setItems([]));
    return () => {
      active = false;
    };
  }, []);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Live Alerts</h3>
        <span className="flex items-center gap-1.5 text-[11px] text-text-secondary">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gains opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-gains" />
          </span>
          live
        </span>
      </div>

      {items === null ? (
        <ul className="mt-3 space-y-2.5">
          {[0, 1, 2].map((i) => (
            <li key={i} className="h-[68px] animate-pulse rounded-xl bg-border/40" />
          ))}
        </ul>
      ) : items.length === 0 ? (
        <div className="mt-6 flex flex-col items-center gap-1 py-6 text-center">
          <span className="text-lg text-gains">✓</span>
          <p className="text-xs text-text-secondary">All clear — no active alerts.</p>
        </div>
      ) : (
        <motion.ul layout className="mt-3 space-y-2.5">
          <AnimatePresence initial={false}>
            {items.map((a, i) => {
              const t = tier[a.tier];
              return (
                <motion.li
                  key={a.id}
                  layout
                  initial={reduce ? false : { opacity: 0, y: 14 }}
                  animate={{
                    opacity: 1,
                    y: 0,
                    transition: reduce
                      ? { duration: 0 }
                      : { delay: 0.06 + i * 0.07, type: "spring", stiffness: 220, damping: 24 },
                  }}
                  whileHover={reduce ? undefined : { y: -2 }}
                  className="group relative overflow-hidden rounded-xl border border-[var(--hairline)] bg-[var(--hover-wash)] p-3.5"
                  style={{ boxShadow: `inset 3px 0 0 ${t.color}` }}
                >
                  {/* tier wash on hover */}
                  <span
                    aria-hidden
                    className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
                    style={{
                      background: `radial-gradient(120% 80% at 0% 0%, color-mix(in srgb, ${t.color} 10%, transparent), transparent 60%)`,
                    }}
                  />
                  <div className="relative flex gap-3">
                    <span
                      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-sm font-bold"
                      style={{
                        backgroundColor: `color-mix(in srgb, ${t.color} 14%, transparent)`,
                        color: t.color,
                        boxShadow: `0 0 18px -6px ${t.color}`,
                      }}
                    >
                      {t.glyph}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <p className="truncate text-sm font-semibold text-text-primary">{a.title}</p>
                        <span className="shrink-0 font-mono text-[10px] text-text-secondary">{relTime(a.time)}</span>
                      </div>
                      <p className="mt-0.5 text-xs leading-snug text-text-secondary">{a.body}</p>
                      <div className="mt-1.5 flex items-center gap-2">
                        <span
                          className="rounded-full px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide"
                          style={{ backgroundColor: `color-mix(in srgb, ${t.color} 12%, transparent)`, color: t.color }}
                        >
                          {t.label}
                        </span>
                        <span className="text-[10px] uppercase tracking-wider text-text-secondary">{a.source}</span>
                      </div>
                    </div>
                  </div>
                </motion.li>
              );
            })}
          </AnimatePresence>
        </motion.ul>
      )}
    </div>
  );
}
