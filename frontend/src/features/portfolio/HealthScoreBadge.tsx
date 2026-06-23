"use client";

import { Sensitive } from "@/features/privacy/Sensitive";
import { getHealthScore, type HealthScoreResult } from "@/lib/apiClient";
import { HealthScoreBreakdown } from "@/features/portfolio/HealthScoreBreakdown";
import { useEffect, useState } from "react";

/**
 * Always-visible Portfolio Health Score for the top bar (Stories 3.8/3.9, FR-6/FR-7). Shows the
 * rule-engine score coloured by the FR-6 thresholds (80+ green, 60–79 amber, <60 red); tapping it
 * opens the breakdown popover (deductions + 30-day trend).
 */
export function HealthScoreBadge() {
  const [result, setResult] = useState<HealthScoreResult | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let active = true;
    getHealthScore()
      .then((r) => active && setResult(r))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const score = result?.score ?? null;
  const color =
    score == null ? "text-text-secondary" : score >= 80 ? "text-gains" : score >= 60 ? "text-warning" : "text-losses";

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex cursor-pointer flex-col items-end leading-tight"
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Health Score</span>
        <Sensitive className={`text-lg font-bold ${color}`}>
          <span className={`font-mono text-lg font-bold tabular-nums ${color}`}>{score ?? "—"}</span>
        </Sensitive>
      </button>
      {open && result && <HealthScoreBreakdown result={result} onClose={() => setOpen(false)} />}
    </div>
  );
}
