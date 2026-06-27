"use client";

import { useEffect, useState } from "react";

import { getLatestBriefing, type Briefing } from "@/lib/apiClient";
import { relTime } from "@/lib/time";

/**
 * Morning Briefing (Epic 8, FR-16) — the pinned card at the top of the dashboard. Shows the latest
 * local-model narrative over the portfolio, overnight news, recommendations, and today's calendar.
 * `undefined` = loading, `null` = none generated yet.
 */
export function BriefingCard() {
  const [briefing, setBriefing] = useState<Briefing | null | undefined>(undefined);

  useEffect(() => {
    let active = true;
    getLatestBriefing()
      .then((b) => active && setBriefing(b))
      .catch(() => active && setBriefing(null));
    return () => {
      active = false;
    };
  }, []);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
          Morning Briefing
        </h3>
        {briefing && (
          <span className="font-mono text-[10px] text-text-secondary">{relTime(briefing.generatedAt)}</span>
        )}
      </div>

      {briefing === undefined ? (
        <div className="mt-3 space-y-2">
          <div className="h-5 w-2/3 animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-full animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-5/6 animate-pulse rounded-lg bg-border/40" />
        </div>
      ) : briefing === null ? (
        <div className="mt-3 flex items-center gap-2 py-2">
          <span className="text-base">🌅</span>
          <p className="text-sm text-text-secondary">Your morning briefing will arrive at 8am.</p>
        </div>
      ) : (
        <div className="mt-2">
          <p className="font-display text-lg font-semibold leading-snug text-text-primary">
            {briefing.headline}
          </p>
          <p className="mt-2 text-sm leading-relaxed text-text-secondary">{briefing.body}</p>
        </div>
      )}
    </div>
  );
}
