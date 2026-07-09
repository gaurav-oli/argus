"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getPaperTrades, type PaperTradeScoreboard } from "@/lib/apiClient";
import { absTime } from "@/lib/time";

/**
 * The Investor persona's autonomous scoreboard (FR-11 follow-up). Instead of asking you to log which
 * calls you took, Agent 5's Investor opens a fixed-notional paper trade on every recommendation and
 * marks it to market at the horizon. This shows the resulting book — win rate, realized return, and
 * the Analyst's post-mortems on losing calls — all built with no input from you.
 */
export function PaperInvestorScoreboard() {
  const [board, setBoard] = useState<PaperTradeScoreboard | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    getPaperTrades()
      .then((b) => active && setBoard(b))
      .catch(() => active && setBoard(null))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, []);

  if (!loaded) {
    return <Skeleton className="h-52" />;
  }
  if (!board) {
    return null;
  }

  const noneClosed = board.closedTrades === 0;
  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">
            The Investor’s track record
          </h3>
          <p className="mt-0.5 text-xs text-text-secondary">
            ${fmt(board.notionalPerTrade, 0)} paper-traded on every call, marked to market at the horizon —
            no input needed.
          </p>
        </div>
        <span className="shrink-0 rounded-full border border-[var(--hairline)] px-2 py-0.5 text-[10px] font-medium text-text-secondary">
          {board.openTrades} open
        </span>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Tile
          label="Win rate"
          value={board.winRatePct === null ? "—" : `${board.winRatePct}%`}
          sub={`${board.wins}/${board.closedTrades} closed`}
        />
        <Tile
          label="Book return"
          value={board.bookReturnPct === null ? "—" : `${signed(board.bookReturnPct)}%`}
          tone={board.bookReturnPct}
          sub={`$${fmt(board.deployed, 0)} deployed`}
        />
        <Tile
          label="Realized P&L"
          value={board.realizedPnl === 0 && noneClosed ? "—" : `$${signed(board.realizedPnl, 2)}`}
          tone={board.realizedPnl}
          sub="pretend money"
        />
      </div>

      {board.openByTicker.length > 0 && <OpenBook board={board} />}

      {noneClosed ? (
        <p className="rounded-lg border border-[var(--hairline)] bg-[var(--hover-wash)] px-3 py-4 text-center text-xs text-text-secondary">
          {board.openTrades > 0
            ? `${board.openTrades} position${board.openTrades === 1 ? "" : "s"} open and being held — the first results land here as they reach their 30-day horizon.`
            : "No trades yet — the Investor opens one on each new recommendation."}
        </p>
      ) : (
        <div className="flex flex-col gap-2 border-t border-[var(--hairline)] pt-3">
          <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">
            Recently closed
          </p>
          <ul className="flex flex-col gap-2.5">
            {board.recent.map((t, i) => (
              <li key={i} className="flex flex-col gap-1">
                <div className="flex items-center gap-3 text-sm">
                  <span className="w-14 shrink-0 font-mono font-semibold text-text-primary">{t.ticker}</span>
                  <span
                    className="w-16 shrink-0 text-[11px] font-semibold uppercase"
                    style={{ color: t.direction === "BEARISH" ? "var(--color-losses)" : "var(--color-gains)" }}
                  >
                    {t.direction === "BEARISH" ? "short" : "long"}
                  </span>
                  <span
                    className="w-16 shrink-0 font-mono tabular-nums"
                    style={{ color: t.won ? "var(--color-gains)" : "var(--color-losses)" }}
                  >
                    {t.returnPct === null ? "—" : `${signed(t.returnPct)}%`}
                  </span>
                  <span
                    className="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold"
                    style={{
                      backgroundColor: t.won ? "color-mix(in srgb, var(--color-gains) 15%, transparent)" : "color-mix(in srgb, var(--color-losses) 15%, transparent)",
                      color: t.won ? "var(--color-gains)" : "var(--color-losses)",
                    }}
                  >
                    {t.won ? "WON" : "LOST"}
                  </span>
                  <span className="ml-auto shrink-0 font-mono text-[10px] text-text-secondary">
                    {absTime(t.closedAt)}
                  </span>
                </div>
                {t.review && (
                  <p className="pl-14 text-[11px] italic leading-snug text-text-secondary">
                    Analyst: {t.review}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </MotionCard>
  );
}

function OpenBook({ board }: { board: PaperTradeScoreboard }) {
  const u = board.openUnrealizedPct;
  return (
    <div className="flex flex-col gap-2 border-t border-[var(--hairline)] pt-3">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">
          Open book · held until horizon
        </p>
        <p className="font-mono text-xs tabular-nums text-text-secondary">
          {board.openTrades} pos · ${fmt(board.openDeployed, 0)} in
          {u != null && (
            <span
              className="ml-2 font-semibold"
              style={{ color: u > 0 ? "var(--color-gains)" : u < 0 ? "var(--color-losses)" : undefined }}
            >
              {signed(u)}% unreal.
            </span>
          )}
        </p>
      </div>
      <ul className="flex flex-col gap-1.5">
        {board.openByTicker.map((p) => (
          <li key={p.ticker} className="flex items-center gap-3 text-sm">
            <span className="w-14 shrink-0 font-mono font-semibold text-text-primary">{p.ticker}</span>
            <span
              className="w-14 shrink-0 text-[11px] font-semibold uppercase"
              style={{ color: p.direction === "BEARISH" ? "var(--color-losses)" : "var(--color-gains)" }}
            >
              {p.direction === "BEARISH" ? "short" : "long"}
            </span>
            <span className="w-24 shrink-0 font-mono text-[11px] text-text-secondary">
              {p.positions}× · ${fmt(p.notional, 0)}
            </span>
            <span
              className="ml-auto shrink-0 font-mono tabular-nums"
              style={{
                color:
                  p.unrealizedPct == null
                    ? undefined
                    : p.unrealizedPct > 0
                      ? "var(--color-gains)"
                      : p.unrealizedPct < 0
                        ? "var(--color-losses)"
                        : undefined,
              }}
            >
              {p.unrealizedPct == null ? "—" : `${signed(p.unrealizedPct)}%`}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Tile({
  label,
  value,
  sub,
  tone,
}: {
  label: string;
  value: string;
  sub: string;
  tone?: number | null;
}) {
  const color =
    tone == null || tone === 0
      ? "var(--color-text-primary)"
      : tone > 0
        ? "var(--color-gains)"
        : "var(--color-losses)";
  return (
    <div className="rounded-xl border border-[var(--hairline)] bg-[var(--hover-wash)] px-4 py-3">
      <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">{label}</p>
      <p className="mt-1 font-display text-2xl font-bold tabular-nums" style={{ color }}>
        {value}
      </p>
      <p className="mt-0.5 text-xs text-text-secondary">{sub}</p>
    </div>
  );
}

function signed(n: number, digits = 1): string {
  return `${n > 0 ? "+" : ""}${fmt(n, digits)}`;
}

function fmt(n: number, digits: number): string {
  return n.toLocaleString("en-CA", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
