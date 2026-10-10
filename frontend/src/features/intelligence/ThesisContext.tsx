"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { PatternActionChip } from "@/features/agents/PatternChecks";
import { agentName, topSignals } from "@/lib/agentNames";
import { getPaperByTicker, getPatternChecks, type PaperTickerView, type PatternCheck } from "@/lib/apiClient";
import { useAutoRefresh } from "@/lib/useAutoRefresh";
import type { RosterRow } from "./TickerRoster";

/**
 * S-B5 — Intelligence as an active thesis board. Each call answers: what to do, how confident, why (the
 * signals that pushed hardest), is the paper Investor in it and how is that going, and how did similar
 * setups do (the S-B4 pattern library's latest check). None of it needs a human Take/Decline.
 */

const REFRESH_MS = 5 * 60_000;

/** The paper book and latest pattern check per ticker, fetched once for the whole board. */
export function useThesisContext() {
  const [paper, setPaper] = useState<PaperTickerView[] | null>(null);
  const [checks, setChecks] = useState<PatternCheck[] | null>(null);
  useAutoRefresh(() => {
    getPaperByTicker()
      .then(setPaper)
      .catch(() => setPaper((prev) => prev ?? []));
    getPatternChecks(undefined, 200)
      .then(setChecks)
      .catch(() => setChecks((prev) => prev ?? []));
  }, REFRESH_MS);
  return useMemo(() => {
    const paperBy = new Map((paper ?? []).map((p) => [p.ticker, p]));
    const patternBy = new Map<string, PatternCheck>();
    for (const c of checks ?? []) if (!patternBy.has(c.ticker)) patternBy.set(c.ticker, c); // newest first
    return { paperBy, patternBy };
  }, [paper, checks]);
}

function signedPct(v: number): string {
  return `${v > 0 ? "+" : ""}${v.toFixed(1)}%`;
}

function money(v: number): string {
  return `${v < 0 ? "−" : v > 0 ? "+" : ""}$${Math.abs(v).toFixed(2)}`;
}

/** "Paper: long 2 legs · $150 · +3.1% live" / "Paper: flat · 3 closed, 2 won · +$4.20" / "Not in the paper book". */
export function paperLine(p: PaperTickerView | undefined): { text: string; tone: "gains" | "losses" | "muted" } {
  if (!p) return { text: "Not in the paper book yet", tone: "muted" };
  if (p.openLegs > 0) {
    const side = p.openDirection === "BEARISH" ? "short" : "long";
    const live = p.unrealizedPct != null ? ` · ${signedPct(p.unrealizedPct)} live` : " · awaiting a live price";
    return {
      text: `Paper: ${side} ${p.openLegs} leg${p.openLegs === 1 ? "" : "s"}${p.openAmount != null ? ` · $${p.openAmount.toFixed(0)}` : ""}${live}`,
      tone: p.unrealizedPct == null ? "muted" : p.unrealizedPct >= 0 ? "gains" : "losses",
    };
  }
  const pnl = p.realizedPnl != null ? ` · ${money(p.realizedPnl)}` : "";
  return {
    text: `Paper: flat · ${p.closedTrades} closed, ${p.wins} won${pnl}`,
    tone: p.realizedPnl == null ? "muted" : p.realizedPnl >= 0 ? "gains" : "losses",
  };
}

const TONE = { gains: "text-gains", losses: "text-losses", muted: "text-text-tertiary" } as const;

/** Compact thesis lines for a "Needs your attention" card. */
export function ThesisLines({ row, paper, pattern }: { row: RosterRow; paper?: PaperTickerView; pattern?: PatternCheck }) {
  const drivers = row.direction ? topSignals(row.signals, row.direction) : [];
  const p = paperLine(paper);
  return (
    <div className="mt-2 flex flex-col gap-1 text-[10.5px] leading-snug">
      {row.odds != null && (
        <p className="text-text-secondary">
          <span className="font-mono text-text-primary">{row.odds}%</span> odds on its side
        </p>
      )}
      {drivers.length > 0 && <p className="text-text-secondary">Driven by {drivers.map((s) => agentName(s.agent)).join(", ")}</p>}
      <p className={TONE[p.tone]}>{p.text}</p>
      {pattern && pattern.action !== "NO_PATTERN" && (
        <p className="text-text-tertiary">
          Pattern: {pattern.matches} similar, {pattern.winRatePct ?? "—"}% won
        </p>
      )}
    </div>
  );
}

/** The full thesis panel at the top of a ticker's page. */
export function ThesisPanel({ row, paper, pattern }: { row: RosterRow; paper?: PaperTickerView; pattern?: PatternCheck }) {
  if (row.action === "WATCH" && !paper && !pattern) return null;
  const drivers = row.direction ? topSignals(row.signals, row.direction, 4) : [];
  const p = paperLine(paper);
  return (
    <div className="mt-4 grid gap-3 border-t border-border pt-4 sm:grid-cols-3">
      <div>
        <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Why · top signals</p>
        {drivers.length > 0 ? (
          <ul className="mt-1 flex flex-col gap-0.5 text-xs text-text-primary">
            {drivers.map((s) => (
              <li key={s.agent} className="flex items-baseline justify-between gap-2">
                <span>{agentName(s.agent)}</span>
                <span className="font-mono text-[10px] text-text-tertiary">{Math.abs(s.weight).toFixed(2)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-xs text-text-secondary">{row.action === "WATCH" ? "Watching — no call yet." : "No signal breakdown stored."}</p>
        )}
        {row.reasons[0] && <p className="mt-1.5 text-[11px] text-text-secondary">{row.reasons[0]}</p>}
      </div>
      <div>
        <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Paper book · the Investor</p>
        <p className={`mt-1 text-xs ${TONE[p.tone]}`}>{p.text}</p>
        {paper && paper.openLegs > 0 && paper.closedTrades > 0 && (
          <p className="mt-0.5 text-[11px] text-text-tertiary">
            Before: {paper.closedTrades} closed, {paper.wins} won{paper.realizedPnl != null && ` · ${money(paper.realizedPnl)}`}
          </p>
        )}
        {paper?.lastResult && (
          <p className="mt-0.5 text-[11px] text-text-tertiary">
            Last close: <span className={paper.lastResult === "WON" ? "text-gains" : "text-losses"}>{paper.lastResult}</span>
            {paper.lastClosedAt && ` · ${new Date(paper.lastClosedAt).toLocaleDateString()}`}
          </p>
        )}
        <Link href="/agents#lessons" className="mt-1 inline-block text-[10px] text-accent underline-offset-4 hover:underline">
          Trade record & lessons →
        </Link>
      </div>
      <div>
        <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Similar setups · pattern library</p>
        {pattern ? (
          <>
            <p className="mt-1 flex flex-wrap items-baseline gap-2 text-xs">
              <PatternActionChip action={pattern.action} />
              <span className="text-[10px] text-text-tertiary">{new Date(pattern.checkedAt).toLocaleDateString()}</span>
            </p>
            <p className="mt-1 text-[11px] text-text-secondary">{pattern.note}</p>
          </>
        ) : (
          <p className="mt-1 text-xs text-text-secondary">Not checked yet — it runs before the Investor&apos;s next entry here.</p>
        )}
        <Link href="/agents#patterns" className="mt-1 inline-block text-[10px] text-accent underline-offset-4 hover:underline">
          All pattern checks →
        </Link>
      </div>
    </div>
  );
}
