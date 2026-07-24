"use client";

import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";

import { getLiveAlerts, type LiveAlert } from "@/lib/apiClient";
import { relTime } from "@/lib/time";
import { SortToggle } from "@/components/ui/SortToggle";

const tier = {
  critical: { color: "var(--color-losses)", label: "Crit", rank: 0 },
  warning: { color: "var(--color-warning)", label: "Watch", rank: 1 },
  info: { color: "var(--color-accent)", label: "Info", rank: 2 },
} as const;

type SortMode = "newest" | "oldest" | "severity";
const SORT_OPTIONS = [
  { value: "newest", label: "Newest" },
  { value: "oldest", label: "Oldest" },
  { value: "severity", label: "Severity" },
] as const satisfies readonly { value: SortMode; label: string }[];

function sortAlerts(items: LiveAlert[], mode: SortMode): LiveAlert[] {
  const byTimeDesc = (a: LiveAlert, b: LiveAlert) => (b.time ?? "").localeCompare(a.time ?? "");
  const sorted = [...items];
  if (mode === "oldest") {
    sorted.sort((a, b) => (a.time ?? "").localeCompare(b.time ?? ""));
  } else if (mode === "severity") {
    sorted.sort((a, b) => tier[a.tier].rank - tier[b.tier].rank || byTimeDesc(a, b));
  } else {
    sorted.sort(byTimeDesc);
  }
  return sorted;
}

/**
 * Live Alerts feed (Epic 9) — wired to /api/alerts/live (latest to oldest by default), which
 * composes real agent output: Stranger Danger warnings, upcoming calendar events, and fresh
 * recommendations. A compact, rule-separated list — one row per alert (tier tag, title, relative
 * time, then a one-line detail) — in place of the earlier boxed-card treatment, which ran ~110px
 * per item and dominated the page for more than a couple of alerts. Sort order (Newest/Oldest/
 * Severity) is client-side over the same fetched set, no re-fetch needed.
 */
export function AlertCards() {
  const reduce = useReducedMotion();
  const [items, setItems] = useState<LiveAlert[] | null>(null);
  const [sortMode, setSortMode] = useState<SortMode>("newest");

  useEffect(() => {
    let active = true;
    getLiveAlerts()
      .then((v) => active && setItems(v))
      .catch(() => active && setItems([]));
    return () => {
      active = false;
    };
  }, []);

  const sorted = useMemo(() => (items ? sortAlerts(items, sortMode) : []), [items, sortMode]);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Live Alerts</h3>
        <div className="flex items-center gap-3">
          {items != null && items.length > 0 && (
            <SortToggle options={SORT_OPTIONS} value={sortMode} onChange={setSortMode} />
          )}
          <span className="flex items-center gap-1.5 text-[11px] text-text-secondary">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gains opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-gains" />
            </span>
            live
          </span>
        </div>
      </div>

      {items === null ? (
        <ul className="mt-3 space-y-2">
          {[0, 1, 2].map((i) => (
            <li key={i} className="h-10 animate-pulse rounded bg-border/40" />
          ))}
        </ul>
      ) : items.length === 0 ? (
        <div className="mt-6 flex flex-col items-center gap-1 py-6 text-center">
          <span className="text-lg text-gains">✓</span>
          <p className="text-xs text-text-secondary">All clear — no active alerts.</p>
        </div>
      ) : (
        <motion.ul layout className="mt-2">
          <AnimatePresence initial={false}>
            {sorted.map((a, i) => {
              const t = tier[a.tier];
              return (
                <motion.li
                  key={a.id}
                  layout
                  initial={reduce ? false : { opacity: 0, y: 8 }}
                  animate={{
                    opacity: 1,
                    y: 0,
                    transition: reduce ? { duration: 0 } : { delay: 0.04 + i * 0.04, duration: 0.25 },
                  }}
                  className="border-b border-[var(--hairline)] py-2.5 last:border-b-0"
                >
                  <div className="flex items-baseline gap-2">
                    <span
                      className="shrink-0 text-[10px] font-semibold uppercase tracking-wide"
                      style={{ color: t.color }}
                    >
                      {t.label}
                    </span>
                    <p className="min-w-0 flex-1 truncate text-sm font-medium text-text-primary">{a.title}</p>
                    <span className="shrink-0 text-[11px] text-text-secondary">{relTime(a.time)}</span>
                  </div>
                  <p className="mt-0.5 truncate text-xs text-text-secondary">
                    {a.body} <span className="text-text-secondary/70">· {a.source}</span>
                  </p>
                </motion.li>
              );
            })}
          </AnimatePresence>
        </motion.ul>
      )}
    </div>
  );
}
