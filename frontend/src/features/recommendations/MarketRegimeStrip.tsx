"use client";

import { getMarketRegime, type MarketRegimeView } from "@/lib/apiClient";
import { useState } from "react";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

const STATE_STYLE: Record<MarketRegimeView["state"], { label: string; cls: string }> = {
  RISK_OFF: { label: "Risk-off", cls: "bg-losses/15 text-losses" },
  RISK_ON: { label: "Risk-on", cls: "bg-gains/15 text-gains" },
  NEUTRAL: { label: "Neutral", cls: "bg-border/60 text-text-secondary" },
  UNKNOWN: { label: "No market data", cls: "bg-border/60 text-text-secondary" },
};

/**
 * One line of tape context above the recommendations: what the market is actually doing today. The
 * recommender now weighs this (a broad-selloff day mutes macro headlines and blocks headline-only
 * sell calls), so the page shows it rather than leaving the reason for a "no call" implicit.
 */
export function MarketRegimeStrip() {
  const [regime, setRegime] = useState<MarketRegimeView | null>(null);

  useAutoRefresh(() => getMarketRegime().then(setRegime).catch(() => {}));

  if (!regime || regime.state === "UNKNOWN") return null;
  const style = STATE_STYLE[regime.state];

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-border bg-surface px-3 py-2 text-[11px]">
      <span className={`rounded px-1.5 py-0.5 font-semibold uppercase tracking-wide ${style.cls}`}>{style.label}</span>
      <span className="text-text-secondary">{regime.summary}</span>
      {regime.state === "RISK_OFF" && (
        <span className="text-text-secondary">
          Shock-day moves often reverse — Argus won&apos;t sell into a selloff on headlines alone.
        </span>
      )}
    </div>
  );
}
