"use client";

import {
  getDeepAnalyses,
  getDeepQueue,
  getDeepScorecard,
  runDeepAnalysis,
  type DeepAnalysisView,
  type DeepQueue,
  type DeepScorecard,
} from "@/lib/apiClient";
import { DeepAnalysisDetail, DeepSummaryLine, ageText } from "@/features/intelligence/DeepAnalysisCard";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Skeleton } from "@/components/ui/Skeleton";
import { useCallback, useEffect, useState } from "react";

/**
 * Agent 11 — the deep analyst's verdicts. Each stock gets a slow, multi-stage analysis (every other agent's
 * evidence, the chart, the fundamentals, four specialist analysts, a skeptic and a portfolio manager) ending in
 * a plain answer: worth buying, wait, or not worth buying — and for a buy, how long to hold. It runs nightly and
 * on demand; a full pass legitimately takes hours, so progress shows here while it works.
 */
export function DeepAnalysisPanel() {
  const [items, setItems] = useState<DeepAnalysisView[] | null>(null);
  const [queue, setQueue] = useState<DeepQueue | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [ticker, setTicker] = useState("");
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    getDeepAnalyses().then(setItems).catch(() => setItems((cur) => cur ?? []));
    getDeepQueue().then(setQueue).catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Poll only while the analyst has work — a full pass takes hours, an idle page shouldn't hammer the API.
  const busy = queue != null && (queue.running != null || queue.queued > 0);
  useEffect(() => {
    if (!busy) return;
    const id = setInterval(refresh, 10_000);
    return () => clearInterval(id);
  }, [busy, refresh]);

  async function analyze(t: string) {
    const clean = t.trim().toUpperCase();
    if (!clean) return;
    setError(null);
    try {
      await runDeepAnalysis(clean);
      setTicker("");
      refresh();
    } catch {
      setError(`Couldn't queue ${clean} — is that a valid ticker?`);
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Deep analysis · Agent 11</h2>
          <p className="mt-1 max-w-xl text-xs text-text-secondary">
            Takes its time: engages every other agent, reads the chart and the financials, argues both sides, then answers whether a stock is
            worth buying — and for how long to hold it.
          </p>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void analyze(ticker);
          }}
          className="flex gap-2"
        >
          <input
            value={ticker}
            onChange={(e) => setTicker(e.target.value)}
            placeholder="Ticker"
            maxLength={8}
            className="w-24 rounded border border-border bg-background px-2 py-1 text-xs uppercase text-text-primary"
          />
          <button type="submit" className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent hover:bg-accent/10">
            Analyze
          </button>
        </form>
      </div>

      {error && <p className="text-xs text-losses">{error}</p>}
      {queue && (queue.running || queue.queued > 0) && (
        <p className="rounded-lg bg-accent/10 px-3 py-2 text-xs text-accent">
          ⏳ Working: {queue.running ?? "starting next"} · {queue.queued} more queued
        </p>
      )}

      {items === null ? (
        <Skeleton className="h-24 w-full" />
      ) : items.length === 0 ? (
        <p className="text-sm text-text-secondary">
          No analyses yet. The first pass over your holdings starts automatically and can take a few hours; you can also analyze any ticker above.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((a) => {
            const isOpen = open === a.ticker;
            return (
              <li key={a.id} className="rounded-lg border border-border">
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? null : a.ticker)}
                  className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-left"
                >
                  <span className="w-14 shrink-0 text-sm font-bold text-text-primary">
                    <Sensitive>{a.ticker}</Sensitive>
                  </span>
                  <DeepSummaryLine a={a} />
                  <span className="min-w-0 flex-1 truncate text-xs text-text-secondary">{a.headline}</span>
                  {a.stale && <span className="text-[10px] text-warning">stale</span>}
                  {a.inProgress && <span className="text-[10px] text-accent">re-analysing: {a.inProgress.stage ?? a.inProgress.status.toLowerCase()}</span>}
                  <span className="text-[10px] text-text-secondary">{ageText(a.finishedAt)}</span>
                </button>
                {isOpen && (
                  <div className="border-t border-border px-3 py-3">
                    <DeepAnalysisDetail a={a} onRerun={() => void analyze(a.ticker)} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <Scorecard />
    </section>
  );
}

function pct(n: number | null | undefined, digits = 1): string {
  return n == null ? "–" : `${n >= 0 ? "+" : ""}${n.toFixed(digits)}%`;
}

/**
 * Agent 11 held to account: every verdict against what the stock actually did next, relative to the S&P 500 (7/30/90 days,
 * and since the call). A "worth buying" is a hit when the stock beat the market; "not worth buying" when it lagged it.
 * Once enough verdicts have matured, a poor record automatically caps the analyst's own conviction.
 */
function Scorecard() {
  const [sc, setSc] = useState<DeepScorecard | null | undefined>(undefined);
  useEffect(() => {
    getDeepScorecard().then(setSc).catch(() => setSc(null));
  }, []);
  if (!sc || sc.totalVerdicts === 0) return null;
  const cell = (v: string, h: number) => sc.cells.find((c) => c.verdict === v && c.horizonDays === h);
  return (
    <details className="rounded-lg border border-border px-3 py-2">
      <summary className="cursor-pointer text-[11px] font-medium text-text-secondary">
        Track record — how Agent 11&apos;s {sc.totalVerdicts} verdicts did against the S&amp;P 500
      </summary>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-left text-[11px]">
          <thead>
            <tr className="text-text-secondary">
              <th className="py-1 pr-3 font-medium">Verdict</th>
              {[7, 30, 90].map((h) => (
                <th key={h} className="py-1 pr-3 font-medium">
                  {h} days
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {(["WORTH_BUYING", "WAIT", "NOT_WORTH_BUYING"] as const).map((v) => (
              <tr key={v} className="border-t border-border/60">
                <td className="py-1 pr-3 text-text-primary">{v === "WORTH_BUYING" ? "Worth buying" : v === "WAIT" ? "Wait" : "Not worth buying"}</td>
                {[7, 30, 90].map((h) => {
                  const c = cell(v, h);
                  return (
                    <td key={h} className="py-1 pr-3 tabular-nums text-text-secondary">
                      {c ? (
                        <>
                          {pct(c.meanExcessPct)}
                          {c.hitRate != null && <> · {Math.round(c.hitRate * 100)}% right</>} <span className="text-[10px]">(n={c.n})</span>
                        </>
                      ) : (
                        "not matured yet"
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-1 text-[10px] text-text-secondary">
        Average return versus the S&amp;P 500 over the period. Needs {sc.minForTrackRecord} matured verdicts of a kind before a poor record starts capping conviction.
      </p>
      <ul className="mt-2 flex flex-col gap-0.5">
        {sc.rows.slice(0, 12).map((r) => (
          <li key={`${r.ticker}-${r.analyzedOn}`} className="flex flex-wrap gap-x-3 text-[11px] text-text-secondary">
            <span className="w-14 font-semibold text-text-primary">
              <Sensitive>{r.ticker}</Sensitive>
            </span>
            <span>{r.verdict === "WORTH_BUYING" ? "worth buying" : r.verdict === "WAIT" ? "wait" : "not worth buying"}</span>
            <span>{r.analyzedOn}</span>
            <span className="tabular-nums">since: {pct(r.sincePct)} ({pct(r.sinceExcessPct)} vs S&amp;P)</span>
          </li>
        ))}
      </ul>
    </details>
  );
}
