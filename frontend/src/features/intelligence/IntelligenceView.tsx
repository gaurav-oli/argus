"use client";

import {
  getInsiderActivity,
  getNewsFeed,
  getSocialSentiment,
  getWebBuzz,
  type InsiderActivity,
  type NewsItem,
  type TickerBuzz,
  type TickerSentiment,
} from "@/lib/apiClient";
import { AgentLabs } from "@/features/intelligence/AgentLabs";
import { BreakingAlerts } from "@/features/intelligence/BreakingAlerts";
import { CommandPalette, type PaletteItem } from "@/features/intelligence/CommandPalette";
import { TickerDetail } from "@/features/intelligence/TickerDetail";
import { TickerRow, useTickerRoster } from "@/features/intelligence/TickerRoster";
import { TickersTable } from "@/features/intelligence/TickersTable";
import { PageHeader } from "@/components/ui/PageHeader";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { ConvictionRing } from "@/components/ui/ConvictionRing";
import { Skeleton } from "@/components/ui/Skeleton";
import { SlidingTabs } from "@/components/ui/SlidingTabs";
import { TiltCard } from "@/components/ui/TiltCard";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useMemo, useState } from "react";

/** "Oct 5, 8:00 AM" in the viewer's own timezone — when a call was first made. */
function callDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

/** Relative age of the latest re-check, so a stale board is obvious at a glance. */
function checkedAgo(iso: string): string {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  return hrs < 48 ? `${hrs}h ago` : `${Math.round(hrs / 24)}d ago`;
}

const VIEWS = [
  { value: "today", label: "Today" },
  { value: "tickers", label: "Tickers" },
  { value: "labs", label: "Agent Labs" },
];

/**
 * Intelligence — rebuilt as a 3-tier command center instead of a flat stack of 15 equal-weight panels
 * (Agents 1-4, 10-15 each used to iterate the whole universe on its own section; a stock's story was
 * scattered across six of them). Now: Today surfaces only what's actionable, Tickers is one dense
 * searchable roster, Agent Labs holds the cross-ticker/reference views, and opening a ticker assembles
 * every agent's read of it on one screen. Session data (news/social/insider/buzz) is still fetched once
 * here and handed down, so Ticker Detail doesn't refetch the whole feed per ticker.
 */
export function IntelligenceView() {
  const [view, setView] = useState("today");
  const [selectedTicker, setSelectedTicker] = useState<string | null>(null);
  const reduce = useReducedMotion();

  const { rows, loading, allTickers } = useTickerRoster();
  const logos = useCompanyLogos(allTickers);

  const [news, setNews] = useState<NewsItem[] | null>(null);
  const [social, setSocial] = useState<TickerSentiment[] | null>(null);
  const [insider, setInsider] = useState<InsiderActivity[] | null>(null);
  const [buzz, setBuzz] = useState<TickerBuzz[] | null>(null);

  useEffect(() => {
    let active = true;
    const load = <T,>(fn: () => Promise<T>, set: (v: T) => void) =>
      fn()
        .then((v) => active && set(v))
        .catch(() => active && set([] as unknown as T));
    load(getNewsFeed, setNews);
    load(getSocialSentiment, setSocial);
    load(getInsiderActivity, setInsider);
    load(getWebBuzz, setBuzz);
    return () => {
      active = false;
    };
  }, []);

  const actionable = useMemo(() => (rows ?? []).filter((r) => r.action !== "WATCH").slice(0, 4), [rows]);
  const condensed = useMemo(() => (rows ?? []).slice(0, 6), [rows]);

  const paletteItems = useMemo<PaletteItem[]>(() => {
    const tickerItems: PaletteItem[] = (rows ?? []).map((r) => ({
      id: `t-${r.ticker}`,
      icon: "→",
      label: `${r.ticker}`,
      hint: r.actionLabel,
      onSelect: () => setSelectedTicker(r.ticker),
    }));
    const labItems: PaletteItem[] = [
      { id: "lab", icon: "◆", label: "Agent Labs — every agent's cross-ticker view", onSelect: () => setView("labs") },
      { id: "all", icon: "▤", label: "All tickers", onSelect: () => setView("tickers") },
    ];
    return [...tickerItems, ...labItems];
  }, [rows]);

  const selectedRoster = rows?.find((r) => r.ticker === selectedTicker);

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        eyebrow="Agents 1–15"
        title="Intelligence"
        subtitle="What's actionable right now, every ticker Argus follows, and how each agent arrived there."
        action={<CommandPalette items={paletteItems} />}
      />

      <AnimatePresence mode="wait">
        {selectedTicker ? (
          <motion.div key="detail" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <TickerDetail
              ticker={selectedTicker}
              roster={selectedRoster}
              news={news ?? []}
              social={social ?? []}
              insider={insider ?? []}
              buzz={buzz ?? []}
              onClose={() => setSelectedTicker(null)}
            />
          </motion.div>
        ) : (
          <motion.div key="shell" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <SlidingTabs id="intel-view" tabs={VIEWS} value={view} onChange={setView} className="mb-6" />

            <AnimatePresence mode="wait">
              {view === "today" && (
                <motion.div
                  key="today"
                  initial={reduce ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -6 }}
                  transition={{ duration: 0.22 }}
                  className="flex flex-col gap-8"
                >
                  <BreakingAlerts />

                  <section>
                    <div className="mb-4 flex items-baseline justify-between">
                      <h2 className="font-display text-base font-bold text-text-primary">Needs your attention</h2>
                      <span className="text-[11px] text-text-tertiary">{actionable.length} actionable call{actionable.length === 1 ? "" : "s"}</span>
                    </div>
                    {loading ? (
                      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                        <Skeleton className="h-28 w-full" />
                        <Skeleton className="h-28 w-full" />
                        <Skeleton className="h-28 w-full" />
                        <Skeleton className="h-28 w-full" />
                      </div>
                    ) : actionable.length === 0 ? (
                      <p className="text-sm text-text-secondary">Nothing actionable right now — Argus is watching, not calling anything.</p>
                    ) : (
                      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                        {actionable.map((r, i) => (
                          <motion.div
                            key={r.ticker}
                            initial={{ opacity: 0, y: 10 }}
                            animate={{ opacity: 1, y: 0 }}
                            transition={{ delay: i * 0.06, type: "spring", stiffness: 260, damping: 24 }}
                          >
                            <TiltCard onClick={() => setSelectedTicker(r.ticker)} className="cursor-pointer rounded-xl border border-border bg-surface p-4">
                              <div className="flex items-center justify-between">
                                <span className="flex items-center gap-2">
                                  <CompanyIcon ticker={r.ticker} logoUrl={logos[r.ticker]} title={r.ticker} size={20} />
                                  <span className="text-[13px] font-semibold text-text-primary">{r.ticker}</span>
                                </span>
                                {r.conviction != null && <ConvictionRing value={r.conviction} size={40} tone={r.action.includes("AVOID") ? "losses" : "accent"} />}
                              </div>
                              <p className="mt-3 text-[11px] leading-relaxed text-text-secondary">
                                {r.actionLabel}
                                {r.holdLabel && ` · hold ~${r.holdLabel}`}
                                {r.deepVerdict?.atRisk && <span className="ml-1 font-semibold text-warning">⚠ at risk</span>}
                              </p>
                              {r.callSince && (
                                <p className="mt-1.5 text-[10.5px] text-text-tertiary" title={r.checkedAt ? `Last re-checked ${new Date(r.checkedAt).toLocaleString()}` : undefined}>
                                  Since {callDate(r.callSince)}
                                  {r.checkedAt && ` · checked ${checkedAgo(r.checkedAt)}`}
                                </p>
                              )}
                            </TiltCard>
                          </motion.div>
                        ))}
                      </div>
                    )}
                  </section>

                  <section>
                    <div className="mb-3 flex items-baseline justify-between">
                      <h2 className="font-display text-base font-bold text-text-primary">Your tickers</h2>
                      <button type="button" onClick={() => setView("tickers")} className="text-xs font-medium text-accent">
                        View all {rows?.length ?? ""} →
                      </button>
                    </div>
                    <div className="rounded-xl border border-border bg-surface px-4">
                      {loading ? (
                        <Skeleton className="h-40 w-full" />
                      ) : condensed.length === 0 ? (
                        <p className="py-4 text-sm text-text-secondary">No tickers scored yet.</p>
                      ) : (
                        condensed.map((r, i) => <TickerRow key={r.ticker} row={r} logoUrl={logos[r.ticker]} index={i} onOpen={setSelectedTicker} dense />)
                      )}
                    </div>
                  </section>

                  <section>
                    <h2 className="mb-3 text-[11px] font-semibold uppercase tracking-wide text-text-tertiary">
                      How Argus is thinking — reference, not today&apos;s action
                    </h2>
                    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                      <button type="button" onClick={() => setView("labs")} className="rounded-lg border border-border p-3 text-left transition-colors hover:border-accent/40">
                        <p className="text-xs font-semibold text-text-primary">Strategy library · 15</p>
                        <p className="mt-1 text-[11px] text-text-secondary">Published strategies, tested here</p>
                      </button>
                      <button type="button" onClick={() => setView("labs")} className="rounded-lg border border-border p-3 text-left transition-colors hover:border-accent/40">
                        <p className="text-xs font-semibold text-text-primary">Trade Learner · 13</p>
                        <p className="mt-1 text-[11px] text-text-secondary">Rules learned from past trades</p>
                      </button>
                      <button type="button" onClick={() => setView("labs")} className="rounded-lg border border-border p-3 text-left transition-colors hover:border-accent/40">
                        <p className="text-xs font-semibold text-text-primary">Trust &amp; Sources</p>
                        <p className="mt-1 text-[11px] text-text-secondary">Source credibility, Stranger Danger</p>
                      </button>
                      <button type="button" onClick={() => setView("labs")} className="rounded-lg border border-border p-3 text-left transition-colors hover:border-accent/40">
                        <p className="text-xs font-semibold text-text-primary">Chart &amp; Fundamentals</p>
                        <p className="mt-1 text-[11px] text-text-secondary">Agents 10, 12, 14 — whole universe</p>
                      </button>
                    </div>
                  </section>
                </motion.div>
              )}

              {view === "tickers" && (
                <motion.div
                  key="tickers"
                  initial={reduce ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -6 }}
                  transition={{ duration: 0.22 }}
                >
                  {loading ? (
                    <Skeleton className="h-96 w-full" />
                  ) : rows && rows.length > 0 ? (
                    <TickersTable rows={rows} logos={logos} onOpen={setSelectedTicker} />
                  ) : (
                    <div className="rounded-xl border border-border bg-surface px-4">
                      <p className="py-6 text-sm text-text-secondary">No tickers scored yet.</p>
                    </div>
                  )}
                </motion.div>
              )}

              {view === "labs" && (
                <motion.div
                  key="labs"
                  initial={reduce ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -6 }}
                  transition={{ duration: 0.22 }}
                >
                  <AgentLabs />
                </motion.div>
              )}
            </AnimatePresence>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
