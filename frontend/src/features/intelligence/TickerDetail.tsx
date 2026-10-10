"use client";

import { ForecastSpread } from "@/features/intelligence/ForecastSpread";
import {
  getChartDetail,
  getFilingsFor,
  getFundamentalsFor,
  getStrategyReadings,
  type ChartDetail,
  type FilingsDetail,
  type FundamentalsDetail,
  type InsiderActivity,
  type NewsItem,
  type StrategyReading,
  type TickerBuzz,
  type TickerSentiment,
} from "@/lib/apiClient";
import { CandlestickChart } from "./CandlestickChart";
import { DeepAnalysisForTicker } from "./DeepAnalysisCard";
import { TickerLessons } from "@/features/agents/TradeLessons";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { ConvictionRing } from "@/components/ui/ConvictionRing";
import { SlidingTabs } from "@/components/ui/SlidingTabs";
import { Tooltip } from "@/components/ui/Tooltip";
import { Skeleton } from "@/components/ui/Skeleton";
import { ACTION_GLOSSARY, VALUATION_GLOSSARY } from "./statusGlossary";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { useState, type ReactNode } from "react";
import { useAutoRefresh } from "@/lib/useAutoRefresh";
import type { RosterRow } from "./TickerRoster";

const TABS = [
  { value: "overview", label: "Overview" },
  { value: "technicals", label: "Technicals · 10" },
  { value: "fundamentals", label: "Fundamentals · 12 & 14" },
  { value: "strategies", label: "Strategies · 15" },
  { value: "news", label: "News & Sentiment" },
];

/**
 * Every agent's view of one ticker, assembled on one screen with tabs — the actual fix for the problem
 * that sent us here: a stock's story used to be scattered across six top-level page sections. Opens as a
 * full-width panel over the roster; its header icon shares a layoutId with the row that opened it, so
 * there's one small moment of genuine shared-element motion rather than a flat, instant swap.
 */
/** How often an open ticker page re-pulls its tabs. */
const DETAIL_REFRESH_MS = 5 * 60_000;

/** Fundamentals refresh nightly; older than this means a run was missed (a refresh is already queued). */
const FUNDAMENTALS_STALE_HOURS = 26;

/** "3h ago" / "2d ago". */
function ago(iso: string): string {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  return hrs < 48 ? `${hrs}h ago` : `${Math.round(hrs / 24)}d ago`;
}

function hoursSince(iso: string): number {
  return (Date.now() - new Date(iso).getTime()) / 3_600_000;
}

/** "Oct 5" for a yyyy-mm-dd date, without a timezone shift. */
function shortDate(isoDate: string): string {
  return new Date(`${isoDate.slice(0, 10)}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/** A small "as of" line under a section, amber when the data is older than it should be. */
function AsOf({ children, stale = false }: { children: ReactNode; stale?: boolean }) {
  return <p className={`text-[10px] ${stale ? "font-semibold text-warning" : "text-text-tertiary"}`}>{children}</p>;
}

/** "4.6%" — how far a support/resistance level sits from the price it was measured from. */
function levelDistance(level: number, price: number): string {
  return `${((Math.abs(level - price) / price) * 100).toFixed(1)}%`;
}

export function TickerDetail({
  ticker,
  roster,
  news,
  social,
  insider,
  buzz,
  onClose,
}: {
  ticker: string;
  roster: RosterRow | undefined;
  news: NewsItem[];
  social: TickerSentiment[];
  insider: InsiderActivity[];
  buzz: TickerBuzz[];
  onClose: () => void;
}) {
  const [tab, setTab] = useState("overview");
  const reduce = useReducedMotion();
  const logos = useCompanyLogos([ticker]);

  const [chart, setChart] = useState<ChartDetail | null | undefined>(undefined);
  const [fundamentals, setFundamentals] = useState<FundamentalsDetail | null | undefined>(undefined);
  const [filings, setFilings] = useState<FilingsDetail | null | undefined>(undefined);
  const [strategies, setStrategies] = useState<StrategyReading[] | undefined>(undefined);

  // Re-pulled every few minutes while open (and on returning to the tab), so live levels, a just-refreshed
  // fundamentals snapshot or a new filing show up without leaving the page. A failed refresh keeps what's
  // on screen. The panel is keyed per ticker by its parent, so a response can't land on another ticker.
  useAutoRefresh(
    () => {
      getChartDetail(ticker)
        .then(setChart)
        .catch(() => setChart((prev) => (prev === undefined ? null : prev)));
      getFundamentalsFor(ticker)
        .then(setFundamentals)
        .catch(() => setFundamentals((prev) => (prev === undefined ? null : prev)));
      getFilingsFor(ticker)
        .then(setFilings)
        .catch(() => setFilings((prev) => (prev === undefined ? null : prev)));
      getStrategyReadings(ticker)
        .then(setStrategies)
        .catch(() => setStrategies((prev) => prev ?? []));
    },
    DETAIL_REFRESH_MS,
    [ticker],
  );

  const tickerNews = news.filter((n) => n.tickers.includes(ticker)).slice(0, 8);
  const tickerSocial = social.find((s) => s.ticker === ticker);
  const tickerInsider = insider.filter((i) => i.ticker === ticker).slice(0, 6);
  const tickerBuzz = buzz.find((b) => b.ticker === ticker);

  return (
    <motion.div
      initial={reduce ? false : { opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: 10 }}
      transition={{ type: "spring", stiffness: 340, damping: 34 }}
      className="rounded-2xl border border-border bg-surface p-6"
    >
      <button type="button" onClick={onClose} className="mb-4 flex items-center gap-1.5 text-xs text-text-tertiary hover:text-text-primary">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        Back to the roster
      </button>

      {/* Header */}
      <div className="flex flex-wrap items-center gap-5 border-b border-border pb-5">
        <motion.span layoutId={`avatar-${ticker}`}>
          <CompanyIcon ticker={ticker} logoUrl={logos[ticker]} title={ticker} size={40} />
        </motion.span>
        <div className="min-w-[180px] flex-1">
          <h1 className="font-display text-xl font-bold text-text-primary">
            {ticker}
          </h1>
          <p className="mt-0.5 text-xs text-text-secondary">{roster?.reason ?? "Followed by Argus"}</p>
        </div>
        {roster?.conviction != null && (
          <div className="flex items-center gap-4">
            <ConvictionRing value={roster.conviction} tone={roster.action.includes("AVOID") ? "losses" : "accent"} />
            <div>
              <Tooltip content={ACTION_GLOSSARY[roster.action] ?? ACTION_GLOSSARY.WATCH}>
                <span
                  className={`rounded px-2.5 py-1 text-[12px] font-semibold ${
                    roster.action.includes("STRONG") ? "bg-gains/20 text-gains" : roster.action === "WATCH" ? "bg-[var(--hover-wash)] text-text-secondary" : roster.action.includes("AVOID") ? "bg-losses/14 text-losses" : "bg-gains/14 text-gains"
                  }`}
                >
                  {roster.actionLabel}
                </span>
              </Tooltip>
              {roster.holdLabel && <p className="mt-1 text-[11px] text-text-secondary">Hold ~{roster.holdLabel}</p>}
            </div>
          </div>
        )}
      </div>

      {roster?.deepVerdict?.atRisk && (
        <motion.div
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: "auto" }}
          className="mt-4 flex items-center gap-2 border-l-2 border-warning bg-warning/[0.06] px-3.5 py-2.5"
        >
          <span className="text-warning">⚠</span>
          <p className="text-xs text-text-primary">
            <strong className="text-warning">Thesis at risk</strong> — {roster.deepVerdict.atRiskReason ?? "Agent 11 is re-checking this verdict."}
          </p>
        </motion.div>
      )}

      {roster?.chart && (roster.chart.support != null || roster.chart.resistance != null) && (
        <div className="mt-4 grid grid-cols-3 gap-4 border-t border-border pt-4">
          <div>
            <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Support</p>
            <p className="font-display text-base font-semibold tabular-nums text-text-primary">
              {roster.chart.support != null ? `$${roster.chart.support.toFixed(2)}` : "—"}
            </p>
            {roster.chart.support != null && (
              <p className="text-[10px] tabular-nums text-text-tertiary">{levelDistance(roster.chart.support, roster.chart.levelsPrice)} below</p>
            )}
          </div>
          <div>
            <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Resistance</p>
            <p className="font-display text-base font-semibold tabular-nums text-text-primary">
              {roster.chart.resistance != null ? `$${roster.chart.resistance.toFixed(2)}` : "—"}
            </p>
            <p className="text-[10px] tabular-nums text-text-tertiary">
              {roster.chart.resistance != null ? `${levelDistance(roster.chart.resistance, roster.chart.levelsPrice)} above` : "none — near its highs"}
            </p>
          </div>
          <div>
            <p className="text-[9.5px] uppercase tracking-wide text-text-tertiary">Valuation · 12</p>
            {roster.valuation ? (
              <Tooltip content={VALUATION_GLOSSARY[roster.valuation] ?? roster.valuation} side="bottom">
                <p className="w-fit font-display text-base font-semibold text-text-primary">{roster.valuation}</p>
              </Tooltip>
            ) : (
              <p className="font-display text-base font-semibold text-text-primary">—</p>
            )}
          </div>
          <p className="col-span-3 -mt-2 text-[10px] text-text-tertiary">
            Measured from ${roster.chart.levelsPrice.toFixed(2)} {roster.chart.levelsLive ? "live" : "(last close — no live price yet)"} · daily
            chart data through {new Date(`${roster.chart.barsThrough}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
          </p>
        </div>
      )}

      {roster?.forecast && roster.action !== "WATCH" && (
        <ForecastSpread ticker={ticker} forecast={roster.forecast} candles={chart === undefined ? null : (chart?.candles ?? [])} />
      )}

      <SlidingTabs id="ticker-detail" tabs={TABS} value={tab} onChange={setTab} className="mt-5" />

      <AnimatePresence mode="wait">
        <motion.div
          key={tab}
          initial={reduce ? false : { opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -4 }}
          transition={{ duration: 0.18 }}
          className="pt-5"
        >
          {tab === "overview" && (
            <div className="flex flex-col gap-4">
              {roster?.reason && <p className="text-[13px] leading-relaxed text-text-primary">{roster.reason}</p>}
              <div className="grid gap-3 sm:grid-cols-2">
                {roster?.chart && (
                  <div className="rounded-lg border border-border p-3.5">
                    <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-text-secondary">Chart · Agent 10</p>
                    <p className="text-xs text-text-secondary">{roster.chart.notes[0] ?? `${roster.chart.bias.toLowerCase()} (${roster.chart.score.toFixed(2)})`}</p>
                  </div>
                )}
                {roster?.deepVerdict?.headline && (
                  <div className="rounded-lg border border-border p-3.5">
                    <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-text-secondary">Deep analysis · Agent 11</p>
                    <p className="text-xs text-text-secondary">{roster.deepVerdict.headline}</p>
                  </div>
                )}
              </div>
              <DeepAnalysisForTicker ticker={ticker} />
              <TickerLessons ticker={ticker} />
            </div>
          )}

          {tab === "technicals" && (
            <div className="flex flex-col gap-3">
              {chart === undefined ? (
                <Skeleton className="h-64 w-full" />
              ) : chart === null ? (
                <p className="text-sm text-text-secondary">Not enough price history yet for a chart study.</p>
              ) : (
                <>
                  <AsOf>
                    Daily chart through {shortDate(chart.study.asOf)} · levels measured from ${chart.levelsPrice.toFixed(2)}{" "}
                    {chart.levelsLive ? "live" : "(last close)"}
                  </AsOf>
                  <CandlestickChart detail={chart} />
                  <ul className="flex flex-col gap-1">
                    {chart.notes.map((n, i) => (
                      <li key={i} className="text-xs text-text-secondary">
                        • {n}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}

          {tab === "fundamentals" && (
            <div className="flex flex-col gap-4">
              {fundamentals === undefined ? (
                <Skeleton className="h-40 w-full" />
              ) : fundamentals === null || !fundamentals.applicable ? (
                <p className="text-sm text-text-secondary">No fundamentals apply (ETF, fund, or not covered).</p>
              ) : (
                <>
                  {(() => {
                    const hours = hoursSince(fundamentals.fetchedAt);
                    return (
                      <AsOf stale={hours > FUNDAMENTALS_STALE_HOURS}>
                        Fundamentals updated {ago(fundamentals.fetchedAt)}
                        {hours > FUNDAMENTALS_STALE_HOURS ? " — older than a day; a refresh is running and this page will pick it up" : " · refreshed nightly"}
                      </AsOf>
                    );
                  })()}
                  {fundamentals.valuation && (
                    <div className="rounded-lg border border-border p-4">
                      <div className="mb-2 flex items-center justify-between">
                        <p className="text-xs font-semibold text-text-primary">Reverse DCF — what the price assumes</p>
                        <span className="rounded bg-[var(--hover-wash)] px-1.5 py-0.5 text-[10px] font-semibold text-text-secondary">{fundamentals.valuation.verdict}</span>
                      </div>
                      <p className="text-xs leading-relaxed text-text-secondary">{fundamentals.valuation.summary}</p>
                    </div>
                  )}
                  {fundamentals.peers && fundamentals.peers.rows.length > 0 && (
                    <div className="rounded-lg border border-border p-4">
                      <p className="mb-2 text-xs font-semibold text-text-primary">Peer comparison</p>
                      <table className="w-full text-left text-xs">
                        <thead className="text-[10px] uppercase text-text-tertiary">
                          <tr>
                            <th className="pb-1.5">Peer</th>
                            <th className="pb-1.5">P/E</th>
                            <th className="pb-1.5">P/S</th>
                            <th className="pb-1.5">EV/EBITDA</th>
                          </tr>
                        </thead>
                        <tbody className="tabular-nums text-text-secondary">
                          {fundamentals.peers.rows.map((p) => (
                            <tr key={p.symbol} className="border-t border-[var(--hairline)]">
                              <td className="py-1.5 font-semibold text-text-primary">{p.symbol}</td>
                              <td className="py-1.5">{p.pe?.toFixed(1) ?? "–"}</td>
                              <td className="py-1.5">{p.ps?.toFixed(1) ?? "–"}</td>
                              <td className="py-1.5">{p.evEbitda?.toFixed(1) ?? "–"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  {fundamentals.notes.length > 0 && (
                    <ul className="flex flex-col gap-1">
                      {fundamentals.notes.map((n, i) => (
                        <li key={i} className="text-xs text-text-secondary">
                          • {n}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
              {filings && filings.digests.length > 0 && (
                <div className="border-l-2 border-gains py-0.5 pl-3.5">
                  <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gains">
                    Filings · Agent 14 — {filings.digests[0].form}, {filings.digests[0].filedAt}
                  </p>
                  <p className="text-xs italic leading-relaxed text-text-secondary">{filings.digests[0].summary}</p>
                </div>
              )}
            </div>
          )}

          {tab === "strategies" && (
            <div className="flex flex-col gap-0">
              {strategies === undefined ? (
                <Skeleton className="h-32 w-full" />
              ) : strategies.length === 0 ? (
                <p className="text-sm text-text-secondary">No validated academic strategies have a current reading for this ticker.</p>
              ) : (
                strategies.map((s, i) => (
                  <motion.div
                    key={s.acronym}
                    initial={{ opacity: 0, x: -6 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: i * 0.04 }}
                    className="border-t border-[var(--hairline)] py-3.5 first:border-t-0"
                  >
                    <div className="mb-1 flex flex-wrap items-center gap-2">
                      <span className="text-[13px] font-semibold text-text-primary">{s.name}</span>
                      <span className="text-[11px] text-text-tertiary">{s.citation}</span>
                      <span className={`ml-auto text-[12px] font-semibold ${s.view > 0 ? "text-gains" : s.view < 0 ? "text-losses" : "text-text-secondary"}`}>
                        {s.direction} · {Math.round(s.percentile * 100)}th pct
                      </span>
                    </div>
                    <p className="text-[11.5px] text-text-secondary">
                      Out-of-sample t={s.measuredTStat.toFixed(2)} over {s.horizonDays}-day holds · reading as of {shortDate(s.asOf)}.
                    </p>
                  </motion.div>
                ))
              )}
            </div>
          )}

          {tab === "news" && (
            <div className="flex flex-col gap-5">
              <div className="flex flex-col">
                {tickerNews.length === 0 ? (
                  <p className="text-sm text-text-secondary">No recent news for this ticker.</p>
                ) : (
                  tickerNews.map((n) => (
                    <div key={n.id} className="flex items-start justify-between gap-3 border-t border-[var(--hairline)] py-2.5 first:border-t-0">
                      <p className="min-w-0 flex-1 truncate text-[12.5px] text-text-primary">{n.headline}</p>
                      <span className="shrink-0 text-[10.5px] tabular-nums text-text-tertiary">{ago(n.publishedAt)}</span>
                      {n.sentimentScore != null && (
                        <span className={`shrink-0 text-[11px] font-semibold tabular-nums ${n.sentimentScore > 0 ? "text-gains" : n.sentimentScore < 0 ? "text-losses" : "text-text-secondary"}`}>
                          {n.sentimentScore > 0 ? "+" : ""}
                          {n.sentimentScore.toFixed(2)}
                        </span>
                      )}
                    </div>
                  ))
                )}
              </div>
              {tickerSocial && (
                <div>
                  <p className="mb-2 text-xs font-semibold text-text-primary">Crowd sentiment · Agent 2</p>
                  <div className="flex items-center gap-2.5">
                    <div className="flex h-1.5 flex-1 overflow-hidden rounded-full bg-[var(--hairline)]">
                      <div className="h-full bg-gains" style={{ width: `${Math.round((tickerSocial.bullish / Math.max(1, tickerSocial.bullish + tickerSocial.bearish)) * 100)}%` }} />
                      <div className="h-full bg-losses" style={{ width: `${100 - Math.round((tickerSocial.bullish / Math.max(1, tickerSocial.bullish + tickerSocial.bearish)) * 100)}%` }} />
                    </div>
                    <span className="shrink-0 font-mono text-[11px] text-text-secondary">{tickerSocial.total} posts · {tickerSocial.mood}</span>
                  </div>
                </div>
              )}
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="rounded-lg border border-border p-3.5">
                  <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-text-secondary">Insider · Agent 4</p>
                  <p className="text-xs text-text-secondary">
                    {tickerInsider.length === 0 ? "No filings in the last 90 days." : `${tickerInsider.length} filing(s), most recent: ${tickerInsider[0].transactionType.toLowerCase()} by ${tickerInsider[0].insiderName ?? "an insider"}${tickerInsider[0].filedAt ? `, filed ${shortDate(tickerInsider[0].filedAt)}` : ""}.`}
                  </p>
                </div>
                <div className="rounded-lg border border-border p-3.5">
                  <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-text-secondary">Web buzz · Agent 3</p>
                  <p className="text-xs text-text-secondary">
                    {tickerBuzz ? `${tickerBuzz.hnStories} HN mentions, ${tickerBuzz.mood.toLowerCase()}.` : "No web attention tracked yet."}
                  </p>
                </div>
              </div>
            </div>
          )}
        </motion.div>
      </AnimatePresence>
    </motion.div>
  );
}
