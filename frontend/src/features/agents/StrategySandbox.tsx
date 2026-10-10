"use client";

import { useMemo, useState } from "react";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getStrategySandbox, type SandboxState, type SandboxStrategy } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { cn } from "@/lib/utils";
import { FilterChips, Pager, usePaged } from "./tableKit";

/**
 * S-B7 — the strategy sandbox. Passing Agent 15's backtest only earns a strategy SHADOW: it makes forward calls
 * that are scored against SPY but never touch live recommendations. 30 resolved calls with ≥55% beating SPY and a
 * positive mean excess → PROMOTED (live); a coin flip or worse → KILLED.
 */

const STATE: Record<SandboxState, { label: string; cls: string }> = {
  SHADOW: { label: "Shadow", cls: "border-[var(--glass-border)] text-text-secondary" },
  CANDIDATE: { label: "Candidate", cls: "border-warning/50 text-warning" },
  PROMOTED: { label: "Promoted · live", cls: "border-gains/50 text-gains" },
  KILLED: { label: "Killed", cls: "border-losses/50 text-losses" },
};

const NEED = 30;

type Filter = "all" | SandboxState;

export function StrategySandbox() {
  const [rows, setRows] = useState<SandboxStrategy[] | null | undefined>(undefined);
  const [filter, setFilter] = useState<Filter>("all");

  useAutoRefresh(
    () =>
      getStrategySandbox()
        .then(setRows)
        .catch(() => setRows((prev) => (prev === undefined ? null : prev))),
    REFRESH.NORMAL,
  );

  const shown = useMemo(() => (rows ?? []).filter((r) => filter === "all" || r.state === filter), [rows, filter]);
  const paged = usePaged(shown, 8);
  const count = (s: SandboxState) => (rows ?? []).filter((r) => r.state === s).length;

  return (
    <MotionCard index={5} interactive={false} className="flex flex-col gap-3" id="sandbox">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">Strategy sandbox</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          A strategy that passes its backtest starts in shadow: its calls are tracked against SPY but don&apos;t touch
          live scores. After {NEED} resolved calls it is promoted (≥55% beat SPY and a positive average) or killed.
        </p>
      </div>
      {rows === undefined ? (
        <Skeleton className="h-32" />
      ) : rows === null ? (
        <p className="py-6 text-center text-xs text-text-secondary">Couldn&apos;t load the sandbox.</p>
      ) : rows.length === 0 ? (
        <p className="py-6 text-center text-xs text-text-secondary">
          Nothing in the sandbox yet — strategies enter it when they pass Agent 15&apos;s weekly backtest.
        </p>
      ) : (
        <>
          <FilterChips
            label="State"
            value={filter}
            onChange={setFilter}
            options={[
              { value: "all", label: "All" },
              { value: "SHADOW", label: "Shadow", count: count("SHADOW") },
              { value: "CANDIDATE", label: "Candidate", count: count("CANDIDATE") },
              { value: "PROMOTED", label: "Promoted", count: count("PROMOTED") },
              { value: "KILLED", label: "Killed", count: count("KILLED") },
            ]}
          />
          <ol className="flex flex-col divide-y divide-[var(--hairline)]">
            {paged.rows.map((r) => {
              const st = STATE[r.state] ?? STATE.SHADOW;
              const progress = Math.min(1, r.resolved / NEED);
              return (
                <li key={r.acronym} className="flex flex-col gap-1.5 py-2.5">
                  <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <span className={cn("border px-1.5 py-px font-mono text-[10px] uppercase tracking-wider", st.cls)}>{st.label}</span>
                    <span className="text-xs font-semibold text-text-primary">{r.name}</span>
                    <span className="font-mono text-[10px] text-text-tertiary">
                      {r.acronym} · {r.horizonDays}d
                    </span>
                    <span className="ml-auto font-mono text-[11px] text-text-secondary">
                      {r.hitPct != null ? `${r.hitPct}% beat SPY` : "—"}
                      {r.meanExcessPct != null && (
                        <span className={r.meanExcessPct > 0 ? "text-gains" : "text-losses"}>
                          {" "}
                          · avg {r.meanExcessPct > 0 ? "+" : ""}
                          {r.meanExcessPct.toFixed(2)}%
                        </span>
                      )}
                    </span>
                  </div>
                  {(r.state === "SHADOW" || r.state === "CANDIDATE") && (
                    <div className="flex items-center gap-2">
                      <div className="h-1 flex-1 bg-[var(--hairline)]" role="progressbar" aria-valuenow={r.resolved} aria-valuemax={NEED} aria-label={`${r.resolved} of ${NEED} resolved shadow calls`}>
                        <div className="h-full bg-accent" style={{ width: `${progress * 100}%` }} />
                      </div>
                      <span className="font-mono text-[10px] text-text-tertiary">
                        {r.resolved}/{NEED} resolved · {r.openCalls} open
                      </span>
                    </div>
                  )}
                  <p className="text-[11px] text-text-secondary">{r.reason}</p>
                </li>
              );
            })}
            {paged.rows.length === 0 && <li className="py-4 text-center text-xs text-text-secondary">No strategies in this state.</li>}
          </ol>
          <Pager p={paged} />
        </>
      )}
    </MotionCard>
  );
}
