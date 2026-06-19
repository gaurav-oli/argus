"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { alerts as seedAlerts } from "@/lib/mockData";

const tierStyle = {
  critical: { dot: "#FF3B5C", ring: "rgba(255,59,92,0.15)" },
  warning: { dot: "#FFB800", ring: "rgba(255,184,0,0.15)" },
  info: { dot: "#00D4FF", ring: "rgba(0,212,255,0.15)" },
} as const;

/**
 * Live alerts feed — cards stagger in, and dismissing one animates it out via
 * AnimatePresence (a preview of how pushed alerts will enter/leave live).
 */
export function AlertsFeed() {
  const [items, setItems] = useState(seedAlerts);
  const reduce = useReducedMotion();

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

      <ul className="mt-3 space-y-2">
        <AnimatePresence initial>
          {items.map((a, i) => {
            const t = tierStyle[a.tier];
            return (
              <motion.li
                key={a.id}
                layout
                initial={reduce ? false : { opacity: 0, y: 14, scale: 0.96 }}
                animate={{ opacity: 1, y: 0, scale: 1, transition: reduce ? { duration: 0 } : { delay: 0.1 + i * 0.09, type: "spring", stiffness: 180, damping: 20 } }}
                exit={{ opacity: 0, x: 40, transition: { duration: 0.22 } }}
                className="relative flex gap-3 rounded-xl border border-white/[0.06] bg-white/[0.02] p-3"
              >
                <span
                  className="mt-1 h-2 w-2 shrink-0 rounded-full"
                  style={{ backgroundColor: t.dot, boxShadow: `0 0 0 4px ${t.ring}` }}
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <p className="truncate text-sm font-medium text-text-primary">{a.title}</p>
                    <span className="shrink-0 text-[10px] text-text-secondary">{a.time}</span>
                  </div>
                  <p className="mt-0.5 text-xs leading-snug text-text-secondary">{a.body}</p>
                </div>
                <button
                  onClick={() => setItems((prev) => prev.filter((x) => x.id !== a.id))}
                  className="absolute right-2 top-2 text-text-secondary/50 transition-colors hover:text-text-primary"
                  aria-label="Dismiss alert"
                >
                  ×
                </button>
              </motion.li>
            );
          })}
        </AnimatePresence>
      </ul>
    </div>
  );
}
