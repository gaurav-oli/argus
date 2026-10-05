"use client";

import { useEffect, useState } from "react";
import {
  getBudgetStatus,
  getOpsSummary,
  getPlatformMode,
  type BudgetStatus,
  type OpsSummary,
  type PlatformModeView,
} from "@/lib/apiClient";

/**
 * Bottom strip — the Terminal Noir status line (desktop only, hidden below `lg`). Real ops
 * telemetry (Epic 9/10): platform mode (NORMAL/DEGRADED), agents active, Haiku spend against the
 * monthly budget, and a live clock, refreshed every 30s. Each segment degrades to "—" on its own
 * if its endpoint fails, so one bad call never blanks the whole line.
 */
export function BottomStrip() {
  const [ops, setOps] = useState<OpsSummary | null>(null);
  const [budget, setBudget] = useState<BudgetStatus | null>(null);
  const [mode, setMode] = useState<PlatformModeView | null>(null);
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    let active = true;
    const load = () => {
      getOpsSummary().then((v) => active && setOps(v)).catch(() => {});
      getBudgetStatus().then((v) => active && setBudget(v)).catch(() => {});
      getPlatformMode().then((v) => active && setMode(v)).catch(() => {});
    };
    load();
    const poll = setInterval(load, 30_000);
    // Clock starts client-side only, so server and first client render agree (no hydration diff).
    const tick = () => setNow(new Date());
    const first = setTimeout(tick, 0);
    const clock = setInterval(tick, 1000);
    return () => {
      active = false;
      clearInterval(poll);
      clearTimeout(first);
      clearInterval(clock);
    };
  }, []);

  const live = (ops?.agentsActive ?? 0) > 0;
  const degraded = mode?.mode === "DEGRADED";
  const budgetTone =
    budget == null ? "" : budget.band === "CRITICAL" || budget.band === "WARNING" ? "text-losses" : budget.band === "NOTICE" ? "text-warning" : "";

  return (
    <footer className="hidden h-8 shrink-0 items-center gap-6 border-t border-[var(--glass-border)] glass-chrome px-6 font-mono text-[11px] uppercase tracking-wider text-text-secondary lg:flex">
      <span className={degraded ? "text-losses" : "text-gains"}>
        <span aria-hidden>●</span> {mode ? `${mode.mode} mode` : "— mode"}
      </span>
      <span>
        <span className={live ? "text-gains" : undefined} aria-hidden>
          ▲{" "}
        </span>
        agents {ops ? `${ops.agentsActive}/${ops.agentsTotal}` : "—"}
      </span>
      <span className={budgetTone}>
        haiku {budget ? `$${budget.spentUsd.toFixed(2)} / ${budget.budgetUsd > 0 ? `$${budget.budgetUsd.toFixed(2)}` : "no cap"}` : ops ? `$${ops.haikuSpendUsd.toFixed(2)}` : "—"}
      </span>
      <span className="ml-auto tabular-nums text-accent">
        {now ? now.toLocaleTimeString("en-CA", { hour12: false }) : "--:--:--"}
      </span>
    </footer>
  );
}
