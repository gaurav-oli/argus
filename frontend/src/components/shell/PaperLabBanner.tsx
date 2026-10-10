"use client";

import Link from "next/link";
import { useState } from "react";
import { getTrustBar, type TrustBarView } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * The persistent paper-lab line (S-B2), on every page under the top bar. It always says what Argus is
 * — recommendations validated on paper trades, never brokerage execution — and shows whether Agent 5's
 * current system has cleared the configured trust bar. If the bar can't be loaded, the paper-lab
 * statement still shows: the framing must never disappear just because a request failed.
 */
export function PaperLabBanner() {
  const [bar, setBar] = useState<TrustBarView | null | undefined>(undefined);

  useAutoRefresh(
    () =>
      getTrustBar()
        .then(setBar)
        .catch(() => setBar(null)),
    REFRESH.NORMAL,
  );

  const cleared = bar?.cleared === true;
  return (
    <div
      role="note"
      aria-label="Paper validation status"
      className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b border-[var(--glass-border)] bg-[#0d0c09] px-4 py-1.5 font-mono text-[11px]"
    >
      <span className="border border-accent px-1.5 py-px font-bold uppercase tracking-[0.14em] text-accent">Paper lab</span>
      <span className="text-text-secondary">
        Recommendations are validated on paper trades · Argus never places orders
      </span>
      <span className="ml-auto flex items-center gap-2">
        {bar === undefined ? null : bar === null ? (
          <span className="text-text-secondary">Trust bar status unavailable</span>
        ) : (
          <>
            <span className={cleared ? "text-gains" : "text-warning"}>{bar.headline}</span>
            <Link
              href="/"
              className="whitespace-nowrap uppercase tracking-wider text-accent underline-offset-4 hover:underline"
              title="See which checks pass on the Paper trust scoreboard"
            >
              {bar.passed}/{bar.total} checks →
            </Link>
          </>
        )}
      </span>
    </div>
  );
}
