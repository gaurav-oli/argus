"use client";

import { getWatching, type WatchItem } from "@/lib/apiClient";
import { useState } from "react";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * Names Argus is tracking but has no clear edge on, each with the reason. This is the honest
 * replacement for the old wall of 50–60% "recommendations": silence is explained, not hidden, and a
 * name only earns a card when independent evidence agrees strongly enough to act on.
 */
export function WatchingList() {
  const [items, setItems] = useState<WatchItem[] | null>(null);

  useAutoRefresh(() =>
    getWatching()
      .then(setItems)
      .catch(() => setItems((prev) => prev ?? [])),
  );

  if (!items || items.length === 0) return null;

  return (
    <details className="rounded-xl border border-border bg-surface p-4">
      <summary className="cursor-pointer text-[11px] font-medium uppercase tracking-wide text-text-secondary">
        Watching — no clear edge ({items.length})
      </summary>
      <ul className="mt-3 flex flex-col gap-2">
        {items.map((w) => (
          <li key={w.id} className="flex items-start gap-3 text-xs">
            <span className="w-14 shrink-0 font-semibold text-text-primary">{w.ticker}</span>
            <span className="w-12 shrink-0 tabular-nums text-text-secondary" title="Conviction score (needs 62 to act)">
              {w.convictionScore ?? "–"}/100
            </span>
            <span className="text-text-secondary">
              {w.reason ?? "No clear edge."}
              {w.sector && <span className="text-text-secondary/70"> · {w.sector}</span>}
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}
