"use client";

import { getLearning, runLearning, type LearnedRuleView, type LearningView } from "@/lib/apiClient";
import { Skeleton } from "@/components/ui/Skeleton";
import { useEffect, useState } from "react";

function effectText(r: LearnedRuleView): string {
  switch (r.kind) {
    case "PENALTY":
      return `−${Math.round(r.effect)} conviction`;
    case "BOOST":
      return `+${Math.round(r.effect)} conviction`;
    case "BLOCK":
      return "blocks the trade";
    case "SIZE":
      return `size ×${r.effect}`;
    default:
      return `hold ≤ ${Math.round(r.effect)} days`;
  }
}

const KIND_CLS: Record<string, string> = {
  PENALTY: "bg-losses/15 text-losses",
  BLOCK: "bg-losses/15 text-losses",
  SIZE: "bg-warning/15 text-warning",
  BOOST: "bg-gains/15 text-gains",
  CAP_HOLD: "bg-warning/15 text-warning",
};

function Rule({ r }: { r: LearnedRuleView }) {
  return (
    <li className="rounded-lg border border-border px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${KIND_CLS[r.kind] ?? "bg-border/60"}`}>{effectText(r)}</span>
        {r.status !== "ACTIVE" && <span className="rounded bg-border/60 px-1.5 py-0.5 text-[10px] text-text-secondary">{r.status.toLowerCase()}</span>}
        <span className="text-[10px] tabular-nums text-text-secondary">
          {r.bets} independent bets
          {r.winRate != null && <> · win {Math.round(r.winRate * 100)}%</>}
          {r.meanExcess != null && <> · avg {r.meanExcess >= 0 ? "+" : ""}{r.meanExcess.toFixed(1)}% vs S&amp;P</>}
          {r.holdoutMeanExcess != null && r.holdoutBets != null && <> · newer trades: {r.holdoutMeanExcess >= 0 ? "+" : ""}{r.holdoutMeanExcess.toFixed(1)}% over {r.holdoutBets}</>}
        </span>
      </div>
      <p className="mt-1 text-xs text-text-primary">{r.description}</p>
      {r.explanation && <p className="mt-0.5 text-[11px] italic text-text-secondary">Why (a hypothesis): {r.explanation}</p>}
      {r.status !== "ACTIVE" && r.note && <p className="mt-0.5 text-[10px] text-text-secondary">{r.note}</p>}
    </li>
  );
}

/**
 * Agent 13 — what Argus has learned from its own wins and losses. Rules are found deterministically over
 * independent bets and only go live if they replicate on newer trades; once live they change the next
 * recommendation, the next paper trade and the next deep analysis. The model only explains the "why".
 */
export function LearningPanel() {
  const [view, setView] = useState<LearningView | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    getLearning()
      .then((v) => active && setView(v))
      .catch(() => active && setView({ report: null, activeRules: [], otherRules: [], running: false }));
    return () => {
      active = false;
    };
  }, []);

  async function learnNow() {
    setBusy(true);
    try {
      await runLearning();
      setTimeout(() => {
        getLearning().then(setView).catch(() => {}).finally(() => setBusy(false));
      }, 8000);
    } catch {
      setBusy(false);
    }
  }

  const r = view?.report ?? null;
  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">What Argus has learned · Agent 13</h2>
          <p className="mt-1 max-w-xl text-xs text-text-secondary">
            Studies every closed paper trade for the situations that win and lose. A lesson only goes live if it also held up on newer trades —
            then it adjusts new recommendations, sizes and blocks paper trades, and is shown to the deep analyst.
          </p>
        </div>
        <button
          type="button"
          disabled={busy || view?.running}
          onClick={() => void learnNow()}
          className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent hover:bg-accent/10 disabled:opacity-50"
        >
          {busy || view?.running ? "Learning…" : "Learn now"}
        </button>
      </div>

      {view === null ? (
        <Skeleton className="h-24 w-full" />
      ) : (
        <>
          {r ? (
            <div className="flex flex-col gap-2 rounded-lg bg-background/50 p-3">
              <p className="text-[11px] tabular-nums text-text-secondary">
                {r.tradesAnalyzed} closed trades · {r.independentBets} independent bets
                {r.baselineWin != null && <> · win rate {Math.round(r.baselineWin * 100)}%</>}
                {r.baselineExcess != null && <> · average {r.baselineExcess >= 0 ? "+" : ""}{r.baselineExcess.toFixed(1)}% vs S&amp;P</>}
              </p>
              {r.narrative && <p className="text-xs leading-relaxed text-text-primary">{r.narrative}</p>}
              <div className="grid gap-3 sm:grid-cols-2">
                {r.losses && (
                  <div>
                    <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-losses">Where it loses</p>
                    <p className="whitespace-pre-line text-[11px] text-text-secondary">{r.losses}</p>
                  </div>
                )}
                {r.wins && (
                  <div>
                    <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-gains">Where it wins</p>
                    <p className="whitespace-pre-line text-[11px] text-text-secondary">{r.wins}</p>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <p className="text-sm text-text-secondary">No learning pass yet — it runs nightly once there are enough closed trades, or press “Learn now”.</p>
          )}

          {view.activeRules.length > 0 ? (
            <div>
              <p className="mb-1.5 text-[10px] font-medium uppercase tracking-wide text-text-secondary">Active lessons ({view.activeRules.length})</p>
              <ul className="flex flex-col gap-2">{view.activeRules.map((x) => <Rule key={x.id} r={x} />)}</ul>
            </div>
          ) : (
            r && <p className="text-xs text-text-secondary">No lesson has replicated on newer trades yet, so none is changing behaviour — the learner is deliberately conservative.</p>
          )}

          {view.otherRules.length > 0 && (
            <details className="rounded-lg border border-border px-3 py-2">
              <summary className="cursor-pointer text-[11px] text-text-secondary">Considered but not active ({view.otherRules.length})</summary>
              <ul className="mt-2 flex flex-col gap-2">{view.otherRules.map((x) => <Rule key={x.id} r={x} />)}</ul>
            </details>
          )}
        </>
      )}
    </section>
  );
}
