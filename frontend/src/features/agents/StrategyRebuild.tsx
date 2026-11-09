"use client";

import { useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { agentName } from "@/lib/agentNames";
import {
  getPostmortemSummary,
  getPostmortemTrades,
  getReportCards,
  getStrategyGates,
  type PostmortemSummary,
  type PostmortemTrade,
  type ReportCard,
  type StrategyGate,
} from "@/lib/apiClient";
import { absTime } from "@/lib/time";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * Strategy rebuild (docs/strategy-rebuild-plan.md): every agent's report card, the pre-registered gates that decide
 * what goes live, and the numeric post-mortems of real and shadow trades. Every score shows its sample size, so a
 * 3-cluster record never looks as solid as a 300-cluster one.
 */

const STATE_LABEL: Record<ReportCard["state"], string> = {
  UNGRADED: "not graded yet",
  GOOD: "earning more say",
  COIN_FLIP: "coin flip — reduced",
  MUTED: "muted",
  FLIPPED: "read the other way",
};

const STATE_COLOR: Record<ReportCard["state"], string> = {
  UNGRADED: "var(--color-text-secondary)",
  GOOD: "var(--color-gains)",
  COIN_FLIP: "var(--color-text-primary)",
  MUTED: "var(--color-losses)",
  FLIPPED: "var(--color-losses)",
};

const GATE_TITLE: Record<string, string> = {
  "phase1-report-card-weights": "Phase 1 · report-card weights",
  "phase3-stretch-rule": "Phase 3 · don't chase run-ups",
  "phase4-defense": "Phase 4 · defense in bad markets",
  "phase4-macro-short": "Phase 4 · macro-event short (shadow)",
  "phase5-core-bucket": "Phase 5 · long-term core bucket",
};

const BUCKETS: { key: string; label: string }[] = [
  { key: "GOOD_RESULT", label: "Good idea, good result" },
  { key: "LUCKY_WIN", label: "Bad idea, lucky win" },
  { key: "BAD_EXECUTION", label: "Good idea, bad execution" },
  { key: "BAD_LUCK", label: "Good idea, bad luck" },
  { key: "BAD_IDEA", label: "Bad idea" },
];

function pts(v: number | null | undefined, digits = 1): string {
  if (v == null) return "—";
  return `${v >= 0 ? "+" : ""}${v.toFixed(digits)}`;
}

function pct(v: number | null | undefined): string {
  return v == null ? "—" : `${Math.round(v * 100)}%`;
}

function parse<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export function ReportCards() {
  const [cards, setCards] = useState<ReportCard[] | null | undefined>(undefined);

  useAutoRefresh(
    () =>
      getReportCards()
        .then(setCards)
        .catch(() => setCards((prev) => (prev === undefined ? null : prev))),
    REFRESH.SLOW,
  );

  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-3">
      <div className="max-w-2xl">
        <h3 className="font-display text-base font-semibold text-text-primary">Agent report cards</h3>
        <p className="mt-0.5 text-xs leading-relaxed text-text-secondary">
          Every prediction is graded — not just the few that became trades. &ldquo;Edge&rdquo; is how far the stock beat
          (or lagged) the S&amp;P 500 after the agent called it, over the agent&rsquo;s own timeframe, averaged over
          independent clusters (one period × sector). An agent needs 30 clusters before its weight moves.
        </p>
      </div>
      {cards === undefined ? (
        <p className="text-xs text-text-secondary">Loading…</p>
      ) : cards === null || cards.length === 0 ? (
        <p className="text-xs text-text-secondary">No grades yet — the grader runs each evening after the close.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase tracking-wider text-text-secondary">
                <th className="py-1 pr-3 font-medium">Agent</th>
                <th className="py-1 pr-3 font-medium">Edge vs S&amp;P</th>
                <th className="py-1 pr-3 font-medium">Right</th>
                <th className="py-1 pr-3 font-medium">Sample</th>
                <th className="py-1 pr-3 font-medium">Weight</th>
                <th className="py-1 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {cards.map((c) => {
                const sits = parse<Record<string, { clusters: number; edge: number; multiplier: number }>>(c.situational, {});
                return (
                  <tr key={c.agent} className="border-t border-[var(--hairline)] align-top">
                    <td className="py-1.5 pr-3">
                      <span className="text-text-primary">{agentName(c.agent)}</span>
                      <span className="block text-[10px] text-text-secondary">{c.horizon_days}-day window</span>
                    </td>
                    <td
                      className="py-1.5 pr-3 font-mono tabular-nums"
                      style={{ color: c.edge_pct == null ? undefined : c.edge_pct >= 0 ? "var(--color-gains)" : "var(--color-losses)" }}
                    >
                      {pts(c.edge_pct)} pts
                    </td>
                    <td className="py-1.5 pr-3 font-mono tabular-nums text-text-primary">{pct(c.hit_rate)}</td>
                    <td className="py-1.5 pr-3 font-mono tabular-nums text-text-secondary">
                      {c.clusters} clusters
                      <span className="block text-[10px]">{c.calls} calls</span>
                    </td>
                    <td className="py-1.5 pr-3 font-mono tabular-nums text-text-primary">
                      ×{(c.pinned_multiplier ?? c.multiplier).toFixed(2)}
                      {c.pinned_multiplier != null && <span className="block text-[10px] text-text-secondary">pinned</span>}
                      {c.pinned_multiplier == null && c.target_multiplier !== c.multiplier && (
                        <span className="block text-[10px] text-text-secondary">→ ×{c.target_multiplier.toFixed(2)}</span>
                      )}
                    </td>
                    <td className="py-1.5">
                      <span style={{ color: STATE_COLOR[c.state] }}>{STATE_LABEL[c.state]}</span>
                      {c.strength_useful === false && (
                        <span className="block text-[10px] text-text-secondary">strong calls no better than weak ones</span>
                      )}
                      {(["RISK_OFF", "CALM"] as const).map((k) =>
                        sits[k] && sits[k].clusters > 0 ? (
                          <span key={k} className="block text-[10px] text-text-secondary">
                            {k === "RISK_OFF" ? "scary market" : "calm market"}: {pts(sits[k].edge)} pts · {sits[k].clusters} cl.
                          </span>
                        ) : null,
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </MotionCard>
  );
}

export function StrategyGates() {
  const [data, setData] = useState<{ killSwitch: boolean; gates: StrategyGate[] } | null | undefined>(undefined);

  useAutoRefresh(
    () =>
      getStrategyGates()
        .then(setData)
        .catch(() => setData((prev) => (prev === undefined ? null : prev))),
    REFRESH.SLOW,
  );

  return (
    <MotionCard index={1} interactive={false} className="flex flex-col gap-3">
      <div className="max-w-2xl">
        <h3 className="font-display text-base font-semibold text-text-primary">What&rsquo;s live, and why</h3>
        <p className="mt-0.5 text-xs leading-relaxed text-text-secondary">
          Each change runs in shadow until it passes a test fixed in advance. Failing a test is a result, not a bug —
          it keeps a rule that doesn&rsquo;t work away from real decisions.
        </p>
      </div>
      {data?.killSwitch && (
        <p className="rounded-md bg-losses/10 px-3 py-2 text-xs text-losses">Kill switch on — every gate reads as not live.</p>
      )}
      {data === undefined ? (
        <p className="text-xs text-text-secondary">Loading…</p>
      ) : data === null || data.gates.length === 0 ? (
        <p className="text-xs text-text-secondary">No gate has been evaluated yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {data.gates.map((g) => (
            <li key={g.name} className="flex flex-col gap-0.5 border-t border-[var(--hairline)] pt-2 text-xs">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-text-primary">{GATE_TITLE[g.name] ?? g.name}</span>
                <span
                  className="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
                  style={{
                    color: g.status === "LIVE" ? "var(--color-gains)" : g.status === "SHADOW" ? "var(--color-text-primary)" : "var(--color-losses)",
                    border: "1px solid var(--hairline)",
                  }}
                >
                  {g.status.toLowerCase()}
                </span>
              </div>
              {g.note && <span className="text-text-secondary">{g.note}</span>}
              {g.evaluated_at && <span className="text-[10px] text-text-secondary/70">checked {absTime(g.evaluated_at)}</span>}
            </li>
          ))}
        </ul>
      )}
    </MotionCard>
  );
}

export function Postmortems() {
  const [days, setDays] = useState(30);
  const [summary, setSummary] = useState<PostmortemSummary | null | undefined>(undefined);
  const [open, setOpen] = useState<{ book: string; bucket: string } | null>(null);
  const [trades, setTrades] = useState<PostmortemTrade[] | null>(null);

  useAutoRefresh(
    () =>
      getPostmortemSummary(days)
        .then(setSummary)
        .catch(() => setSummary((prev) => (prev === undefined ? null : prev))),
    REFRESH.SLOW,
    [days],
  );

  async function openBucket(book: string, bucket: string) {
    if (open?.book === book && open.bucket === bucket) {
      setOpen(null);
      setTrades(null);
      return;
    }
    setOpen({ book, bucket });
    setTrades(null);
    try {
      setTrades(await getPostmortemTrades(book, bucket, days));
    } catch {
      setTrades([]);
    }
  }

  const count = (book: string, bucket: string) =>
    summary?.buckets.find((b) => b.book === book && b.bucket === bucket);

  return (
    <MotionCard index={2} interactive={false} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-2xl">
          <h3 className="font-display text-base font-semibold text-text-primary">Why trades won or lost</h3>
          <p className="mt-0.5 text-xs leading-relaxed text-text-secondary">
            Every closed trade — real and shadow — sorted by numbers only, against the S&amp;P 500. Shadow trades take
            every qualifying call with the same rules, ignoring cash and position limits; the gap between the two is
            what those limits cost or save. Click a count to see the trades.
          </p>
        </div>
        <div className="flex gap-1">
          {[30, 90].map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDays(d)}
              className={`rounded-lg border border-[var(--hairline)] px-2 py-1 text-[11px] ${days === d ? "text-text-primary" : "text-text-secondary"}`}
            >
              {d}d
            </button>
          ))}
        </div>
      </div>

      {summary === undefined ? (
        <p className="text-xs text-text-secondary">Loading…</p>
      ) : summary === null ? (
        <p className="text-xs text-text-secondary">Couldn&rsquo;t load post-mortems.</p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 text-xs">
            {(["real", "shadow"] as const).map((k) => {
              const b = summary.books[k];
              return (
                <div key={k} className="border border-[var(--hairline)] px-3 py-2">
                  <p className="text-[10px] uppercase tracking-wider text-text-secondary">{k === "real" ? "Real paper trades" : "Shadow trades"}</p>
                  <p className="mt-0.5 font-mono tabular-nums text-text-primary">
                    {pts(b.avg_excess)} pts avg vs S&amp;P · {pct(b.win_rate)} beat it
                  </p>
                  <p className="text-[10px] text-text-secondary">
                    {b.closed} closed · {b.open} open
                  </p>
                </div>
              );
            })}
          </div>

          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase tracking-wider text-text-secondary">
                <th className="py-1 pr-3 font-medium">Bucket</th>
                <th className="py-1 pr-3 font-medium">Real</th>
                <th className="py-1 font-medium">Shadow</th>
              </tr>
            </thead>
            <tbody>
              {BUCKETS.map(({ key, label }) => (
                <tr key={key} className="border-t border-[var(--hairline)]">
                  <td className="py-1.5 pr-3 text-text-primary">{label}</td>
                  {(["REAL", "SHADOW"] as const).map((book) => {
                    const row = count(book, key);
                    return (
                      <td key={book} className="py-1.5 pr-3">
                        {row ? (
                          <button
                            type="button"
                            onClick={() => openBucket(book, key)}
                            className="font-mono tabular-nums text-text-primary underline decoration-dotted underline-offset-2"
                          >
                            {row.trades}
                            <span className="ml-1 text-[10px] text-text-secondary">({pts(row.avg_excess)} pts)</span>
                          </button>
                        ) : (
                          <span className="font-mono text-text-secondary">0</span>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>

          {open && (
            <div className="border-t border-[var(--hairline)] pt-2">
              <p className="mb-1 text-[10px] uppercase tracking-wider text-text-secondary">
                {open.book.toLowerCase()} · {BUCKETS.find((b) => b.key === open.bucket)?.label}
              </p>
              {trades === null ? (
                <p className="text-xs text-text-secondary">Loading…</p>
              ) : trades.length === 0 ? (
                <p className="text-xs text-text-secondary">No trades.</p>
              ) : (
                <ul className="flex flex-col gap-1">
                  {trades.map((t) => (
                    <li key={`${t.book}-${t.trade_id}`} className="text-xs">
                      <span className="font-mono text-text-primary">{t.ticker}</span>{" "}
                      <span className="text-text-secondary">
                        {t.direction.toLowerCase()} · {t.entry_date} → {t.closed_date ?? "?"} · {t.exit_reason?.toLowerCase().replace("_", " ")}
                      </span>{" "}
                      <span className="font-mono tabular-nums">{pts(t.excess_pct)} pts</span>
                      {t.hold_excess_pct != null && (
                        <span className="text-text-secondary"> (held: {pts(t.hold_excess_pct)})</span>
                      )}
                      {t.shock_reason && <span className="block text-[10px] text-text-secondary">shock: {t.shock_reason}</span>}
                      {t.relied_on && (
                        <span className="block text-[10px] text-text-secondary">
                          relied on {t.relied_on.split(",").map(agentName).join(", ")}
                          {t.relied_right == null ? " (not graded yet)" : t.relied_right ? " — they were right" : " — they were wrong"}
                        </span>
                      )}
                      {!t.final && <span className="block text-[10px] text-text-secondary/70">provisional: some windows haven&rsquo;t closed</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </MotionCard>
  );
}
