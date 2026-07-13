"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { getLastLogicReview, runLogicReview, type LogicReviewLast } from "@/lib/apiClient";
import { absTime } from "@/lib/time";

/**
 * Analyst Logic Review (Ops) — the recommender's automated "LLM proposes, data disposes" self-tuning.
 * Gemma reviews the closed paper-trade record and proposes per-agent weight-multiplier factors; a
 * backtest over those trades adopts them only if they improve the Brier score without hurting accuracy.
 * This panel shows the latest run and lets you trigger one on demand.
 */
type Proposal = { agent: string; factor: number; why: string };

export function LogicReview() {
  const [last, setLast] = useState<LogicReviewLast | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getLastLogicReview()
      .then((v) => active && setLast(v))
      .catch(() => active && setLast(null));
    return () => {
      active = false;
    };
  }, []);

  async function onRun() {
    setBusy(true);
    setError(null);
    try {
      await runLogicReview();
      setLast(await getLastLogicReview());
    } catch {
      setError("Couldn't run the review just now.");
    } finally {
      setBusy(false);
    }
  }

  const proposals: Proposal[] = parseProposals(last?.proposals);

  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-xl">
          <h3 className="font-display text-base font-semibold text-text-primary">Analyst logic review</h3>
          <p className="mt-0.5 text-xs leading-relaxed text-text-secondary">
            Gemma proposes per-agent weight tweaks; a backtest over your closed trades adopts them only if
            they improve calibration (Brier) without hurting accuracy. Runs automatically after nightly
            tuning — the model suggests, the data decides.
          </p>
        </div>
        <button
          type="button"
          onClick={onRun}
          disabled={busy}
          className="shrink-0 rounded-lg border border-[var(--hairline)] px-3 py-1.5 text-xs font-semibold text-text-primary transition hover:bg-border/20 disabled:opacity-60"
        >
          {busy ? "Reviewing…" : "Run now"}
        </button>
      </div>

      {error && <p className="text-xs italic text-losses">{error}</p>}

      {last === undefined ? (
        <p className="text-xs text-text-secondary">Loading…</p>
      ) : last === null ? (
        <p className="text-xs text-text-secondary">
          Never run yet. It needs a pool of closed paper trades before it can propose anything.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          <div
            className={`rounded-md px-3 py-2 text-xs font-medium ${
              last.adopted ? "bg-gains/10 text-gains" : "bg-border/20 text-text-primary"
            }`}
          >
            <span className="mr-1.5 rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
              style={{ backgroundColor: last.adopted ? "var(--color-gains)" : "var(--color-hairline)",
                color: last.adopted ? "white" : "var(--color-text-secondary)" }}>
              {last.adopted ? "adopted" : "no change"}
            </span>
            {last.reason}
          </div>

          <div className="grid grid-cols-3 gap-3 text-xs">
            <Metric label="Trades reviewed" value={last.sampleSize.toString()} />
            <Metric
              label="Brier (lower=better)"
              value={fmtPair(last.beforeBrier, last.afterBrier, 4)}
            />
            <Metric label="Accuracy" value={fmtPair(last.beforeAccuracy, last.afterAccuracy, 3, true)} />
          </div>

          {proposals.length > 0 && (
            <div>
              <p className="mb-1 text-[10px] uppercase tracking-wider text-text-secondary">Model proposals</p>
              <ul className="flex flex-col gap-1">
                {proposals.map((p) => (
                  <li key={p.agent} className="flex items-baseline gap-2 text-xs">
                    <span className="font-mono text-text-primary">{p.agent}</span>
                    <span
                      className="font-mono font-semibold"
                      style={{ color: p.factor >= 1 ? "var(--color-gains)" : "var(--color-losses)" }}
                    >
                      ×{p.factor.toFixed(2)}
                    </span>
                    {p.why && <span className="truncate text-text-secondary">{p.why}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <p className="text-[10px] text-text-secondary/70">
            Last run {absTime(last.ranAt)}
            {last.model && last.model !== "n/a" ? ` · ${last.model}` : ""}
          </p>
        </div>
      )}
    </MotionCard>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wider text-text-secondary">{label}</p>
      <p className="mt-0.5 font-mono tabular-nums text-text-primary">{value}</p>
    </div>
  );
}

function fmtPair(before: number | null, after: number | null, digits: number, pct = false): string {
  if (before == null || after == null) return "—";
  const f = (v: number) => (pct ? `${(v * 100).toFixed(1)}%` : v.toFixed(digits));
  return `${f(before)} → ${f(after)}`;
}

function parseProposals(raw: string | null | undefined): Proposal[] {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw) as Proposal[];
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}
