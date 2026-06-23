"use client";

import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { getPortfolioValue, type PortfolioSnapshot } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useState } from "react";

const cad = (n: number) =>
  n.toLocaleString("en-CA", { style: "currency", currency: "CAD", maximumFractionDigits: 2 });

/**
 * Live portfolio value (Story 3.4, FR-2). Fetches the initial snapshot, then subscribes to
 * `/topic/portfolio` for sub-second updates as Finnhub price ticks arrive. Total value + total P&L
 * animate smoothly (AnimatedNumber); an after-hours indicator shows when prices are out-of-session.
 * Values are null until the first tick for a holding, in which case we show a waiting state.
 */
export function PortfolioValue() {
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);

  useEffect(() => {
    let active = true;
    getPortfolioValue()
      .then((s) => active && setSnap(s))
      .catch(() => {
        /* initial fetch may fail offline; live ticks will populate it */
      });
    const handle = subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", (s) => setSnap(s));
    return () => {
      active = false;
      handle.disconnect();
    };
  }, []);

  const value = snap?.totalValueCad ?? null;
  const pnl = snap?.totalPnlCad ?? null;
  const gain = (pnl ?? 0) >= 0;

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <h3 className="text-xs uppercase tracking-wide text-text-secondary">Portfolio value (CAD)</h3>
        {snap?.anyAfterHours && (
          <span className="rounded bg-warning/15 px-1.5 py-0.5 text-[11px] font-medium text-warning">
            After hours
          </span>
        )}
      </div>

      {value == null ? (
        <p className="text-3xl font-bold tracking-tight text-text-secondary tabular-nums">—</p>
      ) : (
        <AnimatedNumber
          value={value}
          format={cad}
          className="text-3xl font-bold tracking-tight text-text-primary tabular-nums"
        />
      )}

      {pnl != null && (
        <span
          className={`flex items-center gap-1 text-sm font-medium tabular-nums ${gain ? "text-gains" : "text-losses"}`}
        >
          {gain ? "▲" : "▼"}
          <AnimatedNumber value={Math.abs(pnl)} format={cad} />
          <span className="text-text-secondary">total P&amp;L</span>
        </span>
      )}

      {value == null && (
        <p className="text-xs text-text-secondary">Waiting for live prices (market data connects on the Mini).</p>
      )}
    </div>
  );
}
