"use client";

import { Sensitive } from "@/features/privacy/Sensitive";
import { getHealthScore } from "@/lib/apiClient";
import { useEffect, useState } from "react";

/**
 * Always-visible Portfolio Health Score for the top bar (Story 3.8, FR-6). Fetches the
 * rule-engine score and colours it by the FR-6 thresholds (80+ green, 60–79 amber, <60 red).
 * Tap-to-breakdown + 30-day trend arrive in Story 3.9.
 */
export function HealthScoreBadge() {
  const [score, setScore] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    getHealthScore()
      .then((r) => active && setScore(r.score))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const color =
    score == null ? "text-text-secondary" : score >= 80 ? "text-gains" : score >= 60 ? "text-warning" : "text-losses";

  return (
    <div className="flex flex-col items-end leading-tight">
      <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Health Score</span>
      <Sensitive className={`text-lg font-bold ${color}`}>
        <span className={`font-mono text-lg font-bold tabular-nums ${color}`}>{score ?? "—"}</span>
      </Sensitive>
    </div>
  );
}
