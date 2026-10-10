"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getLessonForTrade, getLessons, type LessonChangeKind, type TradeLesson } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { cn } from "@/lib/utils";
import { FilterChips, Pager, usePaged } from "./tableKit";

/**
 * S-B3 — the lesson every closed paper trade leaves behind: why Argus entered, how it ended, what it
 * taught (the Analyst's post-mortem on a loss; which signals were right on a win), and what changed after
 * (a logic-review weight change, an Agent 13 rule, or an explicit "no change"). Written by the backend's
 * lessons pass a few minutes after each close; "what changed" settles after the nightly jobs run.
 */

const CHANGE: Record<LessonChangeKind, { label: string; cls: string }> = {
  WEIGHTS_ADJUSTED: { label: "Weights adjusted", cls: "border-gains/50 text-gains" },
  RULE_ACTIVATED: { label: "Rule activated", cls: "border-gains/50 text-gains" },
  RULE_RETIRED: { label: "Rule retired", cls: "border-warning/50 text-warning" },
  NO_CHANGE: { label: "No change", cls: "border-[var(--glass-border)] text-text-secondary" },
  PENDING: { label: "Pending tonight's review", cls: "border-[var(--glass-border)] text-text-secondary" },
};

/** The four labelled parts of a lesson. Reused by the feed, the Trade Journal row and the ticker view. */
export function LessonBody({ l, className }: { l: TradeLesson; className?: string }) {
  const change = CHANGE[l.changeKind] ?? CHANGE.PENDING;
  return (
    <dl className={cn("grid grid-cols-1 gap-x-4 gap-y-1.5 text-xs leading-snug sm:grid-cols-[7.5rem_1fr]", className)}>
      <dt className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">Why entered</dt>
      <dd className="text-text-primary">{l.whyEntered}</dd>
      <dt className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">Outcome</dt>
      <dd style={{ color: l.won ? "var(--color-gains)" : "var(--color-losses)" }}>{l.outcome}</dd>
      <dt className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">Lesson</dt>
      <dd className="text-text-primary">{l.lesson}</dd>
      <dt className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">What changed</dt>
      <dd className="flex flex-wrap items-baseline gap-2">
        <span className={cn("border px-1.5 py-px font-mono text-[10px] uppercase tracking-wider", change.cls)}>{change.label}</span>
        {l.changeSummary && l.changeKind !== "PENDING" && <span className="text-text-secondary">{l.changeSummary}</span>}
      </dd>
    </dl>
  );
}

type ResultFilter = "all" | "won" | "lost";
type ChangeFilter = "all" | "changed" | "NO_CHANGE" | "PENDING";

/** "What the Investor learned" — the Agents page feed of recent lessons. */
export function LessonsFeed() {
  const [lessons, setLessons] = useState<TradeLesson[] | null>(null);
  const [result, setResult] = useState<ResultFilter>("all");
  const [change, setChange] = useState<ChangeFilter>("all");

  useAutoRefresh(
    () =>
      getLessons(undefined, 100)
        .then(setLessons)
        .catch(() => setLessons((prev) => prev ?? [])),
    REFRESH.NORMAL,
  );

  const rows = useMemo(() => {
    const changed = (k: LessonChangeKind) => k === "WEIGHTS_ADJUSTED" || k === "RULE_ACTIVATED" || k === "RULE_RETIRED";
    return (lessons ?? [])
      .filter((l) => result === "all" || (result === "won" ? l.won : !l.won))
      .filter((l) => change === "all" || (change === "changed" ? changed(l.changeKind) : l.changeKind === change));
  }, [lessons, result, change]);
  const paged = usePaged(rows, 5);
  const count = (pred: (l: TradeLesson) => boolean) => (lessons ?? []).filter(pred).length;

  return (
    <MotionCard index={2} interactive={false} className="flex flex-col gap-3" id="lessons">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">What the Investor learned</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          Every closed paper trade, win or loss: why Argus went in, how it ended, the lesson, and whether anything changed because of it.
        </p>
      </div>
      {lessons === null ? (
        <Skeleton className="h-40" />
      ) : lessons.length === 0 ? (
        <p className="py-6 text-center text-xs text-text-secondary">
          No lessons yet — one appears a few minutes after each paper trade closes.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <FilterChips
              label="Result"
              value={result}
              onChange={setResult}
              options={[
                { value: "all", label: "All" },
                { value: "won", label: "Wins", count: count((l) => l.won) },
                { value: "lost", label: "Losses", count: count((l) => !l.won) },
              ]}
            />
            <FilterChips
              label="What changed"
              value={change}
              onChange={setChange}
              options={[
                { value: "all", label: "Any outcome" },
                {
                  value: "changed",
                  label: "Changed something",
                  count: count((l) => l.changeKind !== "NO_CHANGE" && l.changeKind !== "PENDING"),
                },
                { value: "NO_CHANGE", label: "No change", count: count((l) => l.changeKind === "NO_CHANGE") },
                { value: "PENDING", label: "Pending", count: count((l) => l.changeKind === "PENDING") },
              ]}
            />
          </div>
          <ol className="flex flex-col gap-3">
            {paged.rows.map((l) => (
              <li key={l.id} className="border border-[var(--hairline)] p-3">
                <p className="mb-2 flex flex-wrap items-baseline gap-x-3 font-mono text-xs">
                  <span className="font-semibold text-accent">{l.ticker}</span>
                  <span className="uppercase text-text-secondary">{l.direction === "BEARISH" ? "short" : "long"}</span>
                  <span className={l.won ? "text-gains" : "text-losses"}>{l.won ? "WON" : "LOST"}</span>
                  <span className="ml-auto text-[10px] text-text-secondary">{new Date(l.closedAt).toLocaleDateString()}</span>
                </p>
                <LessonBody l={l} />
              </li>
            ))}
            {paged.rows.length === 0 && <li className="py-4 text-center text-xs text-text-secondary">No lessons match these filters.</li>}
          </ol>
          <Pager p={paged} />
        </>
      )}
    </MotionCard>
  );
}

/**
 * The lesson for one closed ledger row, fetched when the row opens. Until the lessons pass has written it
 * (a few minutes after the close), it shows `fallback` — the ledger passes the post-mortem it already has.
 */
export function LessonForTrade({ tradeId, fallback }: { tradeId: number; fallback?: React.ReactNode }) {
  const [l, setL] = useState<TradeLesson | null | undefined>(undefined);
  useEffect(() => {
    let active = true;
    getLessonForTrade(tradeId)
      .then((v) => active && setL(v))
      .catch(() => active && setL(null));
    return () => {
      active = false;
    };
  }, [tradeId]);
  if (l === undefined) return <Skeleton className="mt-2 h-16" />;
  if (l === null) return <>{fallback ?? null}</>;
  return <LessonBody l={l} className="mt-2" />;
}

/** Intelligence ticker view: the latest paper lessons on this name, linking to the full feed on Agents. */
export function TickerLessons({ ticker }: { ticker: string }) {
  const [lessons, setLessons] = useState<TradeLesson[] | null>(null);
  useEffect(() => {
    let active = true;
    getLessons(ticker, 3)
      .then((v) => active && setLessons(v))
      .catch(() => active && setLessons([]));
    return () => {
      active = false;
    };
  }, [ticker]);
  if (!lessons || lessons.length === 0) return null;
  return (
    <div className="border border-[var(--hairline)] p-3.5">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-text-secondary">Paper lessons on {ticker}</p>
        <Link href="/agents#lessons" className="font-mono text-[10px] uppercase tracking-wider text-accent underline-offset-4 hover:underline">
          All lessons →
        </Link>
      </div>
      <ol className="flex flex-col gap-3">
        {lessons.map((l) => (
          <li key={l.id}>
            <p className="mb-1 font-mono text-[10px] text-text-secondary">
              <span className={l.won ? "text-gains" : "text-losses"}>{l.won ? "WON" : "LOST"}</span> · closed{" "}
              {new Date(l.closedAt).toLocaleDateString()}
            </p>
            <LessonBody l={l} />
          </li>
        ))}
      </ol>
    </div>
  );
}
