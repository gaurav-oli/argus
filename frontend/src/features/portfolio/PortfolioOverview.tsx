"use client";

import { HoldingsTreemap } from "@/components/dashboard/HoldingsTreemap";
import { cn } from "@/lib/utils";
import { useState } from "react";
import { PortfolioChart } from "./PortfolioChart";

const VIEWS = [
  { key: "value", label: "Value" },
  { key: "composition", label: "Composition" },
] as const;
type View = (typeof VIEWS)[number]["key"];

/**
 * Value-over-time and composition answer different questions but don't need to compete for space —
 * a toggle instead of the two always-on visualisations stacked back to back. Each view unmounts when
 * hidden rather than being hidden via CSS: PortfolioChart's lightweight-charts instance and the
 * treemap both size off their container's rendered dimensions, which a `display: none` panel can't
 * give them, so switching back in re-fetches fresh rather than un-hiding a stale layout.
 */
export function PortfolioOverview() {
  const [view, setView] = useState<View>("value");

  return (
    <div className="flex h-full flex-col">
      <div className="mb-1 flex justify-end">
        <div className="flex gap-1 rounded-lg bg-[var(--hover-wash)] p-0.5" role="tablist" aria-label="Overview view">
          {VIEWS.map((v) => (
            <button
              key={v.key}
              type="button"
              role="tab"
              aria-selected={view === v.key}
              onClick={() => setView(v.key)}
              className={cn(
                "min-h-[28px] cursor-pointer rounded-md px-3 py-1 text-[11px] font-medium transition-colors",
                view === v.key ? "bg-accent/20 text-accent" : "text-text-secondary hover:text-text-primary",
              )}
            >
              {v.label}
            </button>
          ))}
        </div>
      </div>
      {view === "value" ? <PortfolioChart /> : <HoldingsTreemap />}
    </div>
  );
}
