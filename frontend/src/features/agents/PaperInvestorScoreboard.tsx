"use client";

import { Fragment, useCallback, useMemo, useState } from "react";
import { OpenTradeReviewPanel } from "./OpenTradeReviewPanel";

import { Argie } from "@/components/brand/Argie";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { Sensitive } from "@/features/privacy/Sensitive";
import {
  getPaperTrades,
  type ClosedTradeView,
  type OpenPositionView,
  type PaperTradeScoreboard,
} from "@/lib/apiClient";
import { MOOD_LABEL, STREAK_WINDOW, moodForWinRate, streakLine } from "@/lib/argie";
import { cn } from "@/lib/utils";
import { FilterChips, Pager, SortHeader, compareBy, usePaged, useSort } from "./tableKit";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { absTime } from "@/lib/time";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * The Investor persona's autonomous scoreboard (FR-11 follow-up). Instead of asking you to log which
 * calls you took, Agent 5's Investor opens a fixed-notional paper trade on every recommendation and
 * marks it to market at the horizon. This shows the resulting book — win rate, realized return, and
 * the Analyst's post-mortems on losing calls — all built with no input from you. The open book and
 * the closed trades are sortable, paged tables side by side (closed trades filterable, post-mortems
 * opening per row), so a long history never pushes the rest of the page down.
 */
export function PaperInvestorScoreboard() {
  const [board, setBoard] = useState<PaperTradeScoreboard | null>(null);
  const [loaded, setLoaded] = useState(false);

  useAutoRefresh(() =>
    getPaperTrades()
      .then(setBoard)
      .catch(() => {}) // keep the book on screen; a first-load failure stays null
      .finally(() => setLoaded(true)),
  );

  const refreshBoard = useCallback(() => {
    getPaperTrades()
      .then(setBoard)
      .catch(() => {});
  }, []);

  const logos = useCompanyLogos(
    useMemo(
      () => [...(board?.recent ?? []).map((t) => t.ticker), ...(board?.openByTicker ?? []).map((p) => p.ticker)],
      [board],
    ),
  );

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
            <Sensitive className="text-xs">${fmt(board.notionalPerTrade, 0)}</Sensitive> paper-traded on every
            call, watched through the day (stops, trailing stops, profit-taking) and marked to market by its horizon — no
            input needed.
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
          maskSub
        />
        <Tile
          label="Realized P&L"
          value={board.realizedPnl === 0 && noneClosed ? "—" : `$${signed(board.realizedPnl, 2)}`}
          tone={board.realizedPnl}
          sub="pretend money"
          maskValue
        />
      </div>

      {!noneClosed && <Streak trades={board.recent} />}

      <ActiveManagement m={board.management} />
      <OpenTradeReviewPanel onFinished={refreshBoard} />

      <div className="grid grid-cols-1 gap-4 border-t border-[var(--hairline)] pt-3 xl:grid-cols-2">
        {board.openByTicker.length > 0 ? (
          <OpenBook board={board} logos={logos} />
        ) : (
          <p className="text-xs text-text-secondary">No open positions right now.</p>
        )}
        {noneClosed ? (
          <p className="border border-[var(--hairline)] bg-[var(--hover-wash)] px-3 py-4 text-center text-xs text-text-secondary">
            {board.openTrades > 0
              ? `${board.openTrades} position${board.openTrades === 1 ? "" : "s"} open and being held — the first results land here as they reach their 30-day horizon.`
              : "No trades yet — the Investor opens one on each new recommendation."}
          </p>
        ) : (
          <ClosedBook trades={board.recent} logos={logos} />
        )}
      </div>
    </MotionCard>
  );
}

type OpenKey = "ticker" | "notional" | "unrealized";

function OpenBook({ board, logos }: { board: PaperTradeScoreboard; logos: Record<string, string> }) {
  const u = board.openUnrealizedPct;
  const [sort, onSort] = useSort<OpenKey>({ key: "unrealized", dir: "desc" });
  const rows = useMemo(() => {
    const get = {
      ticker: (p: OpenPositionView) => p.ticker,
      notional: (p: OpenPositionView) => p.notional,
      unrealized: (p: OpenPositionView) => p.unrealizedPct,
    }[sort.key];
    return [...board.openByTicker].sort(compareBy(get, sort.dir));
  }, [board.openByTicker, sort]);
  const paged = usePaged(rows, PAGE);

  return (
    <div className="min-w-0">
      <div className="mb-1 flex flex-wrap items-baseline justify-between gap-x-3">
        <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">Open book · watched every 5 min</p>
        <p className="font-mono text-xs tabular-nums text-text-secondary">
          {board.openTrades} pos · <Sensitive className="text-xs">${fmt(board.openDeployed, 0)}</Sensitive> in
          {u != null && (
            <span className="ml-2 font-semibold" style={{ color: tone(u) }}>
              {signed(u)}% unreal.
            </span>
          )}
        </p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[22rem] font-mono text-xs tabular-nums">
          <thead className="border-b border-[var(--hairline)] text-left text-[10px]">
            <tr>
              <SortHeader label="Ticker" k="ticker" sort={sort} onSort={onSort} />
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Side</th>
              <SortHeader label="Size" k="notional" sort={sort} onSort={onSort} />
              <th scope="col" className="py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Price</th>
              <SortHeader label="Unreal." k="unrealized" sort={sort} onSort={onSort} className="text-right" />
            </tr>
          </thead>
          <tbody>
            {paged.rows.map((p) => (
              <tr key={p.ticker} className="border-b border-[var(--hairline)]/60 hover:bg-[var(--hover-wash)]">
                <td className="py-1.5">
                  <span className="flex items-center gap-2">
                    <CompanyIcon ticker={p.ticker} logoUrl={logos[p.ticker]} title={p.ticker} size={16} />
                    <span className="font-semibold text-text-primary">{p.ticker}</span>
                  </span>
                </td>
                <td className="py-1.5 uppercase" style={{ color: p.direction === "BEARISH" ? "var(--color-losses)" : "var(--color-gains)" }}>
                  {p.direction === "BEARISH" ? "short" : "long"}
                </td>
                <td className="py-1.5 text-text-secondary">
                  <Sensitive className="text-xs">
                    <span>
                      {p.positions}× · ${fmt(p.notional, 0)}
                    </span>
                  </Sensitive>
                </td>
                <td className="py-1.5 text-right text-text-secondary">{p.currentPrice == null ? "—" : fmt(p.currentPrice, 2)}</td>
                <td className="py-1.5 text-right font-semibold" style={{ color: tone(p.unrealizedPct) }}>
                  {p.unrealizedPct == null ? "—" : `${signed(p.unrealizedPct)}%`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pager p={paged} />
    </div>
  );
}

type ClosedFilter = "all" | "won" | "lost" | "stop" | "profit" | "flip";

const STOPPED = new Set(["STOP", "TRAILING_STOP"]);
const TURNED = new Set(["THESIS_FLIP", "THESIS_DECAY"]);

/** The label + explanation for an early exit. */
const EXIT_TAG: Record<string, { label: string; title: string; cls: string }> = {
  STOP: { label: "stopped out", title: "The protective stop set at entry was hit", cls: "bg-warning/15 text-warning" },
  TRAILING_STOP: {
    label: "trailing stop",
    title: "A stop that had been raised as the trade moved in its favour was hit — locking in part of the gain",
    cls: "bg-warning/15 text-warning",
  },
  TAKE_PROFIT: { label: "took profit", title: "Half the position was taken off at its sell target; the rest kept running", cls: "bg-gains/15 text-gains" },
  THESIS_FLIP: { label: "Agent 11 flipped", title: "Agent 11 re-analysed the stock and turned against the position", cls: "bg-warning/15 text-warning" },
  THESIS_DECAY: { label: "call reversed", title: "Agent 5's own latest call on the stock now points the other way", cls: "bg-warning/15 text-warning" },
};
type ClosedKey = "ticker" | "return" | "closed";

/** Recently closed trades: filterable, sortable, paged; the Analyst's post-mortem opens per row. */
function ClosedBook({ trades, logos }: { trades: ClosedTradeView[]; logos: Record<string, string> }) {
  const [filter, setFilter] = useState<ClosedFilter>("all");
  const [sort, onSort] = useSort<ClosedKey>({ key: "closed", dir: "desc" });
  const [open, setOpen] = useState<number | null>(null);

  const counts = {
    all: trades.length,
    won: trades.filter((t) => t.won).length,
    lost: trades.filter((t) => !t.won).length,
    stop: trades.filter((t) => STOPPED.has(t.exitReason ?? "")).length,
    profit: trades.filter((t) => t.exitReason === "TAKE_PROFIT").length,
    flip: trades.filter((t) => TURNED.has(t.exitReason ?? "")).length,
  };
  const rows = useMemo(() => {
    const keep = {
      all: () => true,
      won: (t: ClosedTradeView) => t.won,
      lost: (t: ClosedTradeView) => !t.won,
      stop: (t: ClosedTradeView) => STOPPED.has(t.exitReason ?? ""),
      profit: (t: ClosedTradeView) => t.exitReason === "TAKE_PROFIT",
      flip: (t: ClosedTradeView) => TURNED.has(t.exitReason ?? ""),
    }[filter];
    const get = {
      ticker: (t: ClosedTradeView) => t.ticker,
      return: (t: ClosedTradeView) => t.returnPct,
      closed: (t: ClosedTradeView) => t.closedAt,
    }[sort.key];
    // Keep each trade's original index as a stable identity for the expander.
    return trades
      .map((t, i) => ({ t, i }))
      .filter(({ t }) => keep(t))
      .sort((a, b) => compareBy((x: { t: ClosedTradeView }) => get(x.t), sort.dir)(a, b));
  }, [trades, filter, sort]);
  const paged = usePaged(rows, PAGE);

  return (
    <div className="min-w-0">
      <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
        <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">Recently closed</p>
        <FilterChips
          label="Filter closed trades"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: "All", count: counts.all },
            { value: "won", label: "Won", count: counts.won },
            { value: "lost", label: "Lost", count: counts.lost },
            { value: "stop", label: "Stopped", count: counts.stop },
            { value: "profit", label: "Took profit", count: counts.profit },
            { value: "flip", label: "Thesis turned", count: counts.flip },
          ]}
        />
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[24rem] font-mono text-xs tabular-nums">
          <thead className="border-b border-[var(--hairline)] text-left text-[10px]">
            <tr>
              <SortHeader label="Ticker" k="ticker" sort={sort} onSort={onSort} />
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Side</th>
              <SortHeader label="Return" k="return" sort={sort} onSort={onSort} />
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Result</th>
              <SortHeader label="Closed" k="closed" sort={sort} onSort={onSort} className="text-right" />
            </tr>
          </thead>
          <tbody>
            {paged.rows.map(({ t, i }) => {
              const expanded = open === i;
              return (
                <Fragment key={i}>
                  <tr
                    className={cn("border-b border-[var(--hairline)]/60 hover:bg-[var(--hover-wash)]", t.review && "cursor-pointer")}
                    onClick={t.review ? () => setOpen(expanded ? null : i) : undefined}
                  >
                    <td className="py-1.5">
                      <span className="flex items-center gap-2">
                        <CompanyIcon ticker={t.ticker} logoUrl={logos[t.ticker]} title={t.ticker} size={16} />
                        {t.review ? (
                          <button type="button" aria-expanded={expanded} className="font-semibold text-text-primary hover:text-accent">
                            {t.ticker}
                            <span aria-hidden className="ml-1 text-text-secondary">{expanded ? "▾" : "▸"}</span>
                          </button>
                        ) : (
                          <span className="font-semibold text-text-primary">{t.ticker}</span>
                        )}
                      </span>
                    </td>
                    <td className="py-1.5 uppercase" style={{ color: t.direction === "BEARISH" ? "var(--color-losses)" : "var(--color-gains)" }}>
                      {t.direction === "BEARISH" ? "short" : "long"}
                    </td>
                    <td className="py-1.5 font-semibold" style={{ color: t.won ? "var(--color-gains)" : "var(--color-losses)" }}>
                      {t.returnPct === null ? "—" : `${signed(t.returnPct)}%`}
                    </td>
                    <td className="py-1.5">
                      <span className="flex flex-wrap items-center gap-1">
                        <span
                          className="px-1.5 py-0.5 text-[10px] font-semibold"
                          style={{
                            backgroundColor: `color-mix(in srgb, ${t.won ? "var(--color-gains)" : "var(--color-losses)"} 15%, transparent)`,
                            color: t.won ? "var(--color-gains)" : "var(--color-losses)",
                          }}
                        >
                          {t.won ? "WON" : "LOST"}
                        </span>
                        {t.exitReason && EXIT_TAG[t.exitReason] && (
                          <span className={`px-1.5 py-0.5 text-[10px] ${EXIT_TAG[t.exitReason].cls}`} title={EXIT_TAG[t.exitReason].title}>
                            {EXIT_TAG[t.exitReason].label}
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="py-1.5 text-right text-[10px] text-text-secondary">{absTime(t.closedAt)}</td>
                  </tr>
                  {expanded && t.review && (
                    <tr className="border-b border-[var(--hairline)]/60 bg-[var(--hover-wash)]">
                      <td colSpan={5} className="term-line-in px-2 py-2 text-[11px] italic leading-snug text-text-secondary">
                        Analyst: {t.review}
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {paged.rows.length === 0 && (
              <tr>
                <td colSpan={5} className="py-4 text-center text-text-secondary">
                  No trades match this filter.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <Pager p={paged} />
    </div>
  );
}

/**
 * The last closed trades as a ▲▼ strip, newest on the right, with Argie reacting to the most recent
 * ten (lib/argie): the mood is written out (CELEBRATING / HAPPY / WATCHING / WORRIED / SAD / NAPPING)
 * with a line saying why, and Argie naps until there are ten closed trades to judge.
 */
function Streak({ trades }: { trades: ClosedTradeView[] }) {
  const ordered = [...trades].sort((a, b) => a.closedAt.localeCompare(b.closedAt)).slice(-30);
  const wins = ordered.filter((t) => t.won).length;
  const recent = ordered.slice(-STREAK_WINDOW);
  const recentWins = recent.filter((t) => t.won).length;
  const mood = moodForWinRate(recent.length ? (recentWins / recent.length) * 100 : null, recent.length);
  const moodTone =
    mood === "celebrate" || mood === "happy"
      ? "var(--color-gains)"
      : mood === "worried"
        ? "var(--color-warning)"
        : mood === "sad"
          ? "var(--color-losses)"
          : "var(--color-text-secondary)";
  return (
    <div className="flex items-center gap-3 border border-[var(--hairline)] bg-[var(--hover-wash)] px-3 py-2">
      <Argie mood={mood} size={64} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
          <span className="font-display text-2xl uppercase leading-none tracking-[0.06em]" style={{ color: moodTone }}>
            {MOOD_LABEL[mood]}
          </span>
          <span className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">
            Argie · last {Math.min(STREAK_WINDOW, recent.length) || STREAK_WINDOW} trades
          </span>
        </div>
        <p className="mt-0.5 text-xs leading-snug text-text-secondary" aria-live="polite">
          {streakLine(mood, recentWins, recent.length)}
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-xs">
          <span className="text-[10px] uppercase tracking-wider text-text-secondary">Last {ordered.length}</span>
          <span role="img" aria-label={`${wins} won, ${ordered.length - wins} lost, oldest first`} className="tracking-[0.15em]">
            {ordered.map((t, i) => (
              <span key={i} aria-hidden style={{ color: t.won ? "var(--color-gains)" : "var(--color-losses)" }}>
                {t.won ? "▲" : "▼"}
              </span>
            ))}
          </span>
          <span className="text-text-secondary">
            {wins}–{ordered.length - wins}
          </span>
        </div>
      </div>
    </div>
  );
}

function tone(n: number | null): string | undefined {
  if (n == null || n === 0) return undefined;
  return n > 0 ? "var(--color-gains)" : "var(--color-losses)";
}

/** Rows per page in the book tables. */
const PAGE = 8;

function Tile({
  label,
  value,
  sub,
  tone,
  maskValue,
  maskSub,
}: {
  label: string;
  value: string;
  sub: string;
  tone?: number | null;
  maskValue?: boolean;
  maskSub?: boolean;
}) {
  const color =
    tone == null || tone === 0
      ? "var(--color-text-primary)"
      : tone > 0
        ? "var(--color-gains)"
        : "var(--color-losses)";
  const valueEl = (
    <p className="mt-1 font-display text-2xl font-bold tabular-nums" style={{ color }}>
      {value}
    </p>
  );
  const subEl = <p className="mt-0.5 text-xs text-text-secondary">{sub}</p>;
  return (
    <div className="rounded-xl border border-[var(--hairline)] bg-[var(--hover-wash)] px-4 py-3">
      <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">{label}</p>
      {maskValue ? <Sensitive className="text-2xl font-bold">{valueEl}</Sensitive> : valueEl}
      {maskSub ? <Sensitive className="text-xs">{subEl}</Sensitive> : subEl}
    </div>
  );
}

function signed(n: number, digits = 1): string {
  return `${n > 0 ? "+" : ""}${fmt(n, digits)}`;
}

function fmt(n: number, digits: number): string {
  return n.toLocaleString("en-CA", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/**
 * Is watching the book paying off? Coverage of stops on the open book, how trades have ended, and — once early
 * exits reach their original horizon — what they returned against what simply holding would have.
 */
function ActiveManagement({ m }: { m: PaperTradeScoreboard["management"] }) {
  const early = Object.entries(m.exitsByReason).filter(([r]) => r !== "HORIZON");
  const verdict =
    m.measured === 0 || m.avgRealizedPct == null || m.avgHoldPct == null
      ? null
      : m.avgRealizedPct > m.avgHoldPct
        ? { text: "managing is beating holding", color: "var(--color-gains)" }
        : { text: "holding would have done better so far", color: "var(--color-losses)" };
  return (
    <div className="flex flex-col gap-1.5 border-t border-[var(--hairline)] pt-3 font-mono text-[11px] text-text-secondary">
      <p className="text-[10px] font-medium uppercase tracking-wider">Active management</p>
      <p>
        {m.openWithStop}/{m.openTotal} open positions protected by a stop · {m.openTrailing} trailing a gain
      </p>
      {early.length > 0 && (
        <p>
          Early exits:{" "}
          {early.map(([reason, n], i) => (
            <span key={reason}>
              {i > 0 && " · "}
              {n} {EXIT_TAG[reason]?.label ?? reason.toLowerCase()}
            </span>
          ))}
        </p>
      )}
      <p>
        {verdict ? (
          <>
            {m.measured} early exit{m.measured === 1 ? "" : "s"} measured: {signed(m.avgRealizedPct as number)}% realized vs{" "}
            {signed(m.avgHoldPct as number)}% if held —{" "}
            <span className="font-semibold" style={{ color: verdict.color }}>
              {verdict.text}
            </span>
          </>
        ) : (
          "Managing vs holding: measured once early exits reach their original horizon."
        )}
      </p>
    </div>
  );
}
