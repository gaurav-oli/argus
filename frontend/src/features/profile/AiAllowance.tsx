"use client";

import { useState } from "react";
import { Skeleton } from "@/components/ui/Skeleton";
import { getQuota, type QuotaUsage } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * S-C3 — the signed-in person's AI and import use today against their soft daily caps. Everyone on Argus shares
 * one model budget, so each person gets a fair share; caps reset at midnight Toronto time. A cap of 0 means no
 * cap (the admin, by default).
 */
export function AiAllowance() {
  const [rows, setRows] = useState<QuotaUsage[] | null | undefined>(undefined);

  useAutoRefresh(
    () =>
      getQuota()
        .then((q) => setRows(Object.values(q)))
        .catch(() => setRows((prev) => (prev === undefined ? null : prev))),
    REFRESH.NORMAL,
  );

  if (rows === undefined) return <Skeleton className="h-24" />;
  if (rows === null) return <p className="text-xs text-text-secondary">Couldn&apos;t load today&apos;s usage.</p>;
  const unlimited = rows.every((r) => r.cap === 0);
  return (
    <div className="flex flex-col gap-2.5">
      {unlimited && <p className="text-xs text-text-secondary">No daily caps apply to your account.</p>}
      <ul className="flex flex-col gap-2">
        {rows.map((r) => {
          const pct = r.cap > 0 ? Math.min(1, r.used / r.cap) : 0;
          const full = r.cap > 0 && r.used >= r.cap;
          return (
            <li key={r.kind} className="flex flex-col gap-1">
              <div className="flex items-baseline justify-between text-xs">
                <span className="text-text-primary">{r.label.charAt(0).toUpperCase() + r.label.slice(1)}</span>
                <span className={`font-mono text-[11px] ${full ? "text-warning" : "text-text-secondary"}`}>
                  {r.cap > 0 ? `${r.used} / ${r.cap}` : `${r.used} · no cap`}
                </span>
              </div>
              {r.cap > 0 && (
                <div
                  className="h-1 bg-[var(--hairline)]"
                  role="progressbar"
                  aria-valuenow={r.used}
                  aria-valuemax={r.cap}
                  aria-label={`${r.label}: ${r.used} of ${r.cap} used today`}
                >
                  <div className={`h-full ${full ? "bg-warning" : "bg-accent"}`} style={{ width: `${pct * 100}%` }} />
                </div>
              )}
            </li>
          );
        })}
      </ul>
      <p className="text-[11px] text-text-tertiary">Resets at midnight (Toronto time).</p>
    </div>
  );
}
