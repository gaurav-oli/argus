"use client";

import {
  getRecommendations,
  getWatching,
  type RecommendationCard,
  type WatchItem,
} from "@/lib/apiClient";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import type { ForecastInput } from "./ForecastSpread";
import { Tooltip } from "@/components/ui/Tooltip";
import { ACTION_GLOSSARY, CHART_GLOSSARY, DEEP_GLOSSARY, DEEP_VERDICT_GLOSSARY, VALUATION_GLOSSARY } from "./statusGlossary";
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
  /** The call's model odds + horizon + price levels, for the forecast spread. Null for a WATCH. */
  forecast: ForecastInput | null;
  /** When this call was first made, and when the latest review pass last re-checked it. Null for a WATCH. */
  callSince: string | null;
  checkedAt: string | null;
}

const ACTION_CLS: Record<string, string> = {
  STRONG_BUY: "bg-gains/20 text-gains",
  BUY: "bg-gains/14 text-gains",
  AVOID: "bg-losses/14 text-losses",
  STRONG_AVOID: "bg-losses/20 text-losses",
  WATCH: "bg-[var(--hover-wash)] text-text-secondary",
};

/** How often an open page re-pulls the roster, so a new six-hourly review pass shows up without a reload. */
const REFRESH_MS = 5 * 60_000;

/** Fetches and merges actionable recommendations + watched names into one roster, ranked by conviction. */
export function useTickerRoster() {
  const [recs, setRecs] = useState<RecommendationCard[] | null>(null);
  const [watching, setWatching] = useState<WatchItem[] | null>(null);

  useEffect(() => {
    let active = true;
    // A failed refresh keeps what's on screen; only a failed first load falls back to empty.
    const load = () => {
      getRecommendations()
        .then((v) => active && setRecs(v))
        .catch(() => active && setRecs((prev) => prev ?? []));
      getWatching()
        .then((v) => active && setWatching(v))
        .catch(() => active && setWatching((prev) => prev ?? []));
    };
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      active = false;
      clearInterval(timer);
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
        forecast:
          r.holdDays != null && r.holdDays > 0
            ? { bullProbability: r.bullProbability, direction: r.direction, holdDays: r.holdDays, priceGuidance: r.priceGuidance }
            : null,
        callSince: r.callSince ?? r.createdAt,
        checkedAt: r.createdAt,
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
        forecast: null,
        callSince: null,
        checkedAt: null,
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

/** Agent 11's verdict colours: only a real buy case reads green. */
const DEEP_CLS: Record<string, string> = {
  WORTH_BUYING: "text-gains",
  WAIT: "text-text-secondary",
  NOT_WORTH_BUYING: "text-losses",
};

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
  showConviction = false,
}: {
  row: RosterRow;
  logoUrl?: string;
  index: number;
  onOpen: (ticker: string) => void;
  dense?: boolean;
  /** Adds a 0-100 conviction column (the Tickers table, which has a header to label it). */
  showConviction?: boolean;
}) {
  return (
    <motion.button
      type="button"
      onClick={() => onOpen(row.ticker)}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.025, type: "spring", stiffness: 300, damping: 26 }}
      className={`grid w-full ${showConviction ? "grid-cols-[1.5fr_0.9fr_0.6fr_0.9fr_0.9fr_0.9fr]" : "grid-cols-[1.5fr_0.9fr_0.9fr_0.9fr_0.9fr]"} items-center gap-2 border-b border-[var(--hairline)] py-2.5 text-left transition-colors hover:bg-[var(--hover-wash)] last:border-b-0`}
    >
      <span className="flex min-w-0 items-center gap-2.5">
        <motion.span layoutId={`avatar-${row.ticker}`} className="shrink-0">
          <CompanyIcon ticker={row.ticker} logoUrl={logoUrl} title={row.ticker} size={dense ? 20 : 22} />
        </motion.span>
        <span className="truncate text-[13px] font-semibold text-text-primary">{row.ticker}</span>
      </span>
      <Tooltip content={ACTION_GLOSSARY[row.action] ?? ACTION_GLOSSARY.WATCH}>
        <span className={`w-fit rounded px-1.5 py-0.5 text-[10.5px] font-semibold ${ACTION_CLS[row.action] ?? ACTION_CLS.WATCH}`}>
          {row.actionLabel}
        </span>
      </Tooltip>
      {showConviction && (
        <span className={`font-mono text-[12px] tabular-nums ${row.conviction == null ? "text-text-tertiary" : "text-text-primary"}`}>
          {row.conviction ?? "—"}
        </span>
      )}
      <Tooltip content={row.deepVerdict?.atRisk ? DEEP_GLOSSARY.atRisk : row.deepVerdict ? DEEP_VERDICT_GLOSSARY[row.deepVerdict.verdict] : DEEP_GLOSSARY.none}>
        <span className={`w-fit text-[12px] ${row.deepVerdict?.atRisk ? "text-warning" : row.deepVerdict ? DEEP_CLS[row.deepVerdict.verdict] : "text-text-tertiary"}`}>
          {row.deepVerdict?.atRisk ? "⚠ at risk" : row.deepVerdict ? row.deepVerdict.verdictLabel.toLowerCase() : "—"}
        </span>
      </Tooltip>
      {row.chart ? (
        <Tooltip content={CHART_GLOSSARY[row.chart.bias]}>
          <span className={`w-fit text-[12px] ${biasColor[row.chart.bias]}`}>
            {biasArrow[row.chart.bias]} {row.chart.bias.toLowerCase()}
          </span>
        </Tooltip>
      ) : (
        <span className="text-[12px] text-text-tertiary">—</span>
      )}
      {row.valuation ? (
        <Tooltip content={VALUATION_GLOSSARY[row.valuation] ?? row.valuation}>
          <span className="w-fit text-[12px] text-text-tertiary">{row.valuation}</span>
        </Tooltip>
      ) : (
        <span className="text-[12px] text-text-tertiary">—</span>
      )}
    </motion.button>
  );
}
