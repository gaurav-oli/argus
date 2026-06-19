"use client";

import { useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";

import { alerts as seedAlerts } from "@/lib/mockData";

const tier = {
  critical: { color: "#FF3B5C", label: "Critical", glyph: "!" },
  warning: { color: "#FFB800", label: "Watch", glyph: "▲" },
  info: { color: "#00D4FF", label: "Info", glyph: "i" },
} as const;

/**
 * Live alerts as interactive cards. Each card carries a tier accent + icon,
 * expands on click (layout-animated) to reveal detail + actions, lifts on
 * hover, and animates out when dismissed.
 */
export function AlertCards() {
  const reduce = useReducedMotion();
  const [items, setItems] = useState(seedAlerts);
  const [open, setOpen] = useState<string | null>(seedAlerts[0]?.id ?? null);

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

      <motion.ul layout className="mt-3 space-y-2.5">
        <AnimatePresence initial={false}>
          {items.map((a, i) => {
            const t = tier[a.tier];
            const isOpen = open === a.id;
            return (
              <motion.li
                key={a.id}
                layout
                initial={reduce ? false : { opacity: 0, y: 14 }}
                animate={{ opacity: 1, y: 0, transition: reduce ? { duration: 0 } : { delay: 0.08 + i * 0.08, type: "spring", stiffness: 200, damping: 22 } }}
                exit={{ opacity: 0, x: 60, transition: { duration: 0.22 } }}
                whileHover={reduce ? undefined : { y: -2 }}
                onClick={() => setOpen((cur) => (cur === a.id ? null : a.id))}
                className="group relative cursor-pointer overflow-hidden rounded-xl border border-white/[0.06] bg-white/[0.02] p-3.5"
                style={{ boxShadow: isOpen ? `inset 3px 0 0 ${t.color}, 0 8px 30px -12px ${t.color}55` : `inset 3px 0 0 ${t.color}` }}
              >
                {/* subtle tier wash */}
                <span
                  aria-hidden
                  className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
                  style={{ background: `radial-gradient(120% 80% at 0% 0%, ${t.color}14, transparent 60%)` }}
                />
                <div className="relative flex gap-3">
                  <span
                    className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg font-bold"
                    style={{ backgroundColor: `${t.color}1f`, color: t.color }}
                  >
                    {t.glyph}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <p className="truncate text-sm font-semibold text-text-primary">{a.title}</p>
                      <span className="shrink-0 text-[10px] text-text-secondary">{a.time}</span>
                    </div>
                    <p className="mt-0.5 text-xs leading-snug text-text-secondary">{a.body}</p>

                    <AnimatePresence initial={false}>
                      {isOpen && (
                        <motion.div
                          initial={{ height: 0, opacity: 0 }}
                          animate={{ height: "auto", opacity: 1 }}
                          exit={{ height: 0, opacity: 0 }}
                          transition={{ duration: reduce ? 0 : 0.28, ease: [0.22, 1, 0.36, 1] }}
                          className="overflow-hidden"
                        >
                          <p className="mt-2 text-xs leading-relaxed text-text-primary/80">{a.detail}</p>
                          <div className="mt-2.5 flex flex-wrap items-center gap-2">
                            {a.actions.map((label, idx) => (
                              <button
                                key={label}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  if (idx === a.actions.length - 1) setItems((p) => p.filter((x) => x.id !== a.id));
                                }}
                                className="rounded-lg px-2.5 py-1 text-[11px] font-medium transition-colors"
                                style={
                                  idx === 0
                                    ? { backgroundColor: t.color, color: "#0A0A0F" }
                                    : { backgroundColor: "rgba(255,255,255,0.06)", color: "#E8E8F0" }
                                }
                              >
                                {label}
                              </button>
                            ))}
                            <span className="ml-auto text-[10px] uppercase tracking-wider text-text-secondary">{a.source}</span>
                          </div>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </div>
                </div>
              </motion.li>
            );
          })}
        </AnimatePresence>
      </motion.ul>

      {items.length === 0 && <p className="mt-6 text-center text-xs text-text-secondary">All clear — no active alerts.</p>}
    </div>
  );
}
