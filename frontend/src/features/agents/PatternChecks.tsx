"use client";

import { useMemo, useState } from "react";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getPatternChecks, type PatternAction, type PatternCheck } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { cn } from "@/lib/utils";
import { FilterChips, Pager, usePaged } from "./tableKit";

/**
 * S-B4 — the pattern library's log: before each paper entry the Investor looks up similar past setups, and
 * this shows what it found and what it did about it (skipped, halved the size, tightened the stop, or
 * proceeded). A skipped entry leaves no trade behind, so this is the only place it shows.
 */

export const PATTERN_ACTION: Record<PatternAction, { label: string; cls: string }> = {
  SKIP: { label: "Skipped", cls: "border-losses/50 text-losses" },
  SIZE_DOWN: { label: "Half size", cls: "border-warning/50 text-warning" },
  TIGHTEN_STOP: { label: "Tighter stop", cls: "border-warning/50 text-warning" },
  PROCEED: { label: "Proceeded", cls: "border-gains/50 text-gains" },
  NO_PATTERN: { label: "No prior pattern", cls: "border-[var(--glass-border)] text-text-secondary" },
};

export function PatternActionChip({ action }: { action: PatternAction }) {
  const a = PATTERN_ACTION[action] ?? PATTERN_ACTION.NO_PATTERN;
  return <span className={cn("border px-1.5 py-px font-mono text-[10px] uppercase tracking-wider", a.cls)}>{a.label}</span>;
}

type Filter = "all" | "acted" | PatternAction;

export function PatternChecks() {
  const [checks, setChecks] = useState<PatternCheck[] | null>(null);
  const [filter, setFilter] = useState<Filter>("all");

  useAutoRefresh(
    () =>
      getPatternChecks(undefined, 200)
        .then(setChecks)
        .catch(() => setChecks((prev) => prev ?? [])),
    REFRESH.NORMAL,
  );

  const acted = (c: PatternCheck) => c.action === "SKIP" || c.action === "SIZE_DOWN" || c.action === "TIGHTEN_STOP";
  const rows = useMemo(
    () =>
      (checks ?? []).filter((c) => filter === "all" || (filter === "acted" ? acted(c) : c.action === filter)),
    [checks, filter],
  );
  const paged = usePaged(rows, 8);
  const count = (pred: (c: PatternCheck) => boolean) => (checks ?? []).filter(pred).length;

  return (
    <MotionCard index={3} interactive={false} className="flex flex-col gap-3" id="patterns">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">Pattern check before each trade</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          Before every paper entry the Investor looks up similar past setups. Mostly losers → skip; weak → half
          size; often stopped out → tighter stop. Fewer than 5 matches → no prior pattern, and the trade goes ahead.
        </p>
      </div>
      {checks === null ? (
        <Skeleton className="h-32" />
      ) : checks.length === 0 ? (
        <p className="py-6 text-center text-xs text-text-secondary">No pattern checks yet — one is logged at the next paper entry.</p>
      ) : (
        <>
          <FilterChips
            label="Advice"
            value={filter}
            onChange={setFilter}
            options={[
              { value: "all", label: "All" },
              { value: "acted", label: "Changed the trade", count: count(acted) },
              { value: "SKIP", label: "Skipped", count: count((c) => c.action === "SKIP") },
              { value: "PROCEED", label: "Proceeded", count: count((c) => c.action === "PROCEED") },
              { value: "NO_PATTERN", label: "No pattern", count: count((c) => c.action === "NO_PATTERN") },
            ]}
          />
          <ol className="flex flex-col divide-y divide-[var(--hairline)]">
            {paged.rows.map((c) => (
              <li key={c.id} className="flex flex-col gap-1 py-2 text-xs sm:flex-row sm:items-baseline sm:gap-3">
                <span className="flex shrink-0 items-baseline gap-2 font-mono sm:w-56">
                  <PatternActionChip action={c.action} />
                  <span className="font-semibold text-accent">{c.ticker}</span>
                  <span className="text-[10px] uppercase text-text-secondary">{c.direction === "BEARISH" ? "short" : "long"}</span>
                </span>
                <span className="min-w-0 flex-1 text-text-primary">{c.note}</span>
                <span className="shrink-0 font-mono text-[10px] text-text-secondary">{new Date(c.checkedAt).toLocaleString()}</span>
              </li>
            ))}
            {paged.rows.length === 0 && <li className="py-4 text-center text-xs text-text-secondary">No checks match this filter.</li>}
          </ol>
          <Pager p={paged} />
        </>
      )}
    </MotionCard>
  );
}
