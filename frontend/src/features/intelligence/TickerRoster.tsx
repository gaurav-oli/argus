"use client";

import {
  getRecommendations,
  getWatching,
  type RecommendationCard,
  type WatchItem,
} from "@/lib/apiClient";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Sensitive } from "@/features/privacy/Sensitive";
import { motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";

export type RosterAction = "STRONG_BUY" | "BUY" | "AVOID" | "STRONG_AVOID" | "WATCH";

/** One ticker's merged picture for the roster — an actionable recommendation, or a watched name with no edge yet. */
export interface RosterRow {
  ticker: string;
  action: RosterAction;
  actionLabel: string;
  conviction: number | null;
  holdLabel: string | null;
  deepVerdict: RecommendationCard["deep"] | null;
  chart: RecommendationCard["chart"] | null;
  valuation: string | null;
  reason: string | null;
}

const ACTION_CLS: Record<string, string> = {
  STRONG_BUY: "bg-gains/20 text-gains",
  BUY: "bg-gains/14 text-gains",
  AVOID: "bg-losses/14 text-losses",
  STRONG_AVOID: "bg-losses/20 text-losses",
  WATCH: "bg-[var(--hover-wash)] text-text-secondary",
};

/** Fetches and merges actionable recommendations + watched names into one roster, ranked by conviction. */
export function useTickerRoster() {
  const [recs, setRecs] = useState<RecommendationCard[] | null>(null);
  const [watching, setWatching] = useState<WatchItem[] | null>(null);

  useEffect(() => {
    let active = true;
    getRecommendations()
      .then((v) => active && setRecs(v))
      .catch(() => active && setRecs([]));
    getWatching()
      .then((v) => active && setWatching(v))
      .catch(() => active && setWatching([]));
    return () => {
      active = false;
    };
  }, []);

  const rows = useMemo<RosterRow[] | null>(() => {
    if (recs === null || watching === null) return null;
    const byTicker = new Map<string, RosterRow>();
    for (const r of recs) {
      byTicker.set(r.ticker, {
        ticker: r.ticker,
        action: (r.action ?? "WATCH") as RosterAction,
        actionLabel: r.actionLabel ?? "Watch",
        conviction: r.convictionScore,
        holdLabel: r.holdDays != null ? `${r.holdDays}d` : null,
        deepVerdict: r.deep,
        chart: r.chart,
        valuation: r.valuation,
        reason: r.thesis,
      });
    }
    for (const w of watching) {
      if (byTicker.has(w.ticker)) continue;
      byTicker.set(w.ticker, {
        ticker: w.ticker,
        action: "WATCH",
        actionLabel: "Watch",
        conviction: w.convictionScore,
        holdLabel: null,
        deepVerdict: null,
        chart: null,
        valuation: null,
        reason: w.reason,
      });
    }
    return [...byTicker.values()].sort((a, b) => {
      const rank = (x: RosterRow) => (x.action === "WATCH" ? 1 : 0);
      if (rank(a) !== rank(b)) return rank(a) - rank(b);
      return (b.conviction ?? 0) - (a.conviction ?? 0);
    });
  }, [recs, watching]);

  const allTickers = useMemo(() => rows?.map((r) => r.ticker) ?? [], [rows]);

  return { rows, loading: rows === null, allTickers };
}

const biasColor: Record<string, string> = {
  BULLISH: "text-gains",
  BEARISH: "text-losses",
  NEUTRAL: "text-text-secondary",
};
const biasArrow: Record<string, string> = { BULLISH: "▲", BEARISH: "▼", NEUTRAL: "▬" };

/**
 * One ticker row, dense enough for a 20-name list and legible enough to scan — a ticker/logo, the
 * agent-5 action, and compact chips for the agents with something to say. The avatar shares a layoutId
 * with the one in {@link TickerDetail}'s header, so opening a row sends its icon flying up into place —
 * a small, deliberate "shared element" touch rather than the whole row trying to become the whole page.
 */
export function TickerRow({
  row,
  logoUrl,
  index,
  onOpen,
  dense = false,
}: {
  row: RosterRow;
  logoUrl?: string;
  index: number;
  onOpen: (ticker: string) => void;
  dense?: boolean;
}) {
  return (
    <motion.button
      type="button"
      onClick={() => onOpen(row.ticker)}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.025, type: "spring", stiffness: 300, damping: 26 }}
      className="grid w-full grid-cols-[1.5fr_0.9fr_0.9fr_0.9fr_0.9fr] items-center gap-2 border-b border-[var(--hairline)] py-2.5 text-left transition-colors hover:bg-[var(--hover-wash)] last:border-b-0"
    >
      <span className="flex min-w-0 items-center gap-2.5">
        <motion.span layoutId={`avatar-${row.ticker}`} className="shrink-0">
          <CompanyIcon ticker={row.ticker} logoUrl={logoUrl} title={row.ticker} size={dense ? 20 : 22} />
        </motion.span>
        <span className="truncate text-[13px] font-semibold text-text-primary">
          <Sensitive>{row.ticker}</Sensitive>
        </span>
      </span>
      <span className={`w-fit rounded px-1.5 py-0.5 text-[10.5px] font-semibold ${ACTION_CLS[row.action] ?? ACTION_CLS.WATCH}`}>
        {row.actionLabel}
      </span>
      <span className={`text-[12px] ${row.deepVerdict?.atRisk ? "text-warning" : row.deepVerdict ? "text-gains" : "text-text-tertiary"}`}>
        {row.deepVerdict?.atRisk ? "⚠ at risk" : row.deepVerdict ? "worth buying" : "—"}
      </span>
      <span className={`text-[12px] ${row.chart ? biasColor[row.chart.bias] : "text-text-tertiary"}`}>
        {row.chart ? `${biasArrow[row.chart.bias]} ${row.chart.bias.toLowerCase()}` : "—"}
      </span>
      <span className="text-[12px] text-text-tertiary">{row.valuation ?? "—"}</span>
    </motion.button>
  );
}
