"use client";

import { useEffect, useState } from "react";

import {
  getLatestBriefing,
  getMarketPulse,
  refreshMarketPulse,
  type Briefing,
  type MarketPulse,
} from "@/lib/apiClient";
import { absTime } from "@/lib/time";

/**
 * Morning Briefing (Epic 8, FR-16) — the pinned card at the top of the dashboard. Shows the latest
 * local-model narrative over the portfolio, overnight news, recommendations, and today's calendar,
 * plus an on-demand "market pulse" that summarizes the market-impacting news captured so far. The
 * Refresh button re-scans the news and re-summarizes; if nothing new arrived it says so. Timestamps
 * are absolute (exact "as of" time) rather than relative. `undefined` = loading, `null` = none yet.
 */
export function BriefingCard() {
  const [briefing, setBriefing] = useState<Briefing | null | undefined>(undefined);
  const [pulse, setPulse] = useState<MarketPulse | null | undefined>(undefined);
  const [refreshing, setRefreshing] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getLatestBriefing()
      .then((b) => active && setBriefing(b))
      .catch(() => active && setBriefing(null));
    getMarketPulse()
      .then((p) => active && setPulse(p))
      .catch(() => active && setPulse(null));
    return () => {
      active = false;
    };
  }, []);

  async function onRefresh() {
    if (refreshing) return;
    setRefreshing(true);
    setNote(null);
    try {
      const next = await refreshMarketPulse();
      setPulse(next);
      if (!next.hasUpdates) {
        setNote("No major updates since we last checked.");
      }
    } catch {
      setNote("Couldn't refresh right now — try again in a moment.");
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
          Morning Briefing
        </h3>
        {briefing && (
          <span className="font-mono text-[10px] text-text-secondary">as of {absTime(briefing.generatedAt)}</span>
        )}
      </div>

      {briefing === undefined ? (
        <div className="mt-3 space-y-2">
          <div className="h-5 w-2/3 animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-full animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-5/6 animate-pulse rounded-lg bg-border/40" />
        </div>
      ) : briefing === null ? (
        <div className="mt-3 flex items-center gap-2 py-2">
          <span className="text-base">🌅</span>
          <p className="text-sm text-text-secondary">Your morning briefing will arrive at 8am.</p>
        </div>
      ) : (
        <div className="mt-2">
          <p className="font-display text-lg font-semibold leading-snug text-text-primary">
            {briefing.headline}
          </p>
          <p className="mt-2 text-sm leading-relaxed text-text-secondary">{briefing.body}</p>
        </div>
      )}

      <MarketPulseSection pulse={pulse} refreshing={refreshing} note={note} onRefresh={onRefresh} />
    </div>
  );
}

function MarketPulseSection({
  pulse,
  refreshing,
  note,
  onRefresh,
}: {
  pulse: MarketPulse | null | undefined;
  refreshing: boolean;
  note: string | null;
  onRefresh: () => void;
}) {
  return (
    <div className="mt-4 border-t border-border/50 pt-3">
      <div className="flex items-center justify-between">
        <div className="flex items-baseline gap-2">
          <h4 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
            Market Pulse
          </h4>
          {pulse && (
            <span className="font-mono text-[10px] text-text-secondary">as of {absTime(pulse.generatedAt)}</span>
          )}
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="flex items-center gap-1 rounded-md px-2 py-1 text-[11px] font-medium text-accent transition hover:bg-accent/[0.08] disabled:opacity-50"
        >
          <RefreshIcon spinning={refreshing} />
          {refreshing ? "Checking…" : "Refresh"}
        </button>
      </div>

      {pulse === undefined ? (
        <div className="mt-2 space-y-2">
          <div className="h-3.5 w-full animate-pulse rounded bg-border/40" />
          <div className="h-3.5 w-4/5 animate-pulse rounded bg-border/40" />
        </div>
      ) : pulse === null ? (
        <p className="mt-2 text-sm text-text-secondary">
          Tap Refresh to summarize the market-impacting news captured so far.
        </p>
      ) : (
        <p className="mt-2 text-sm leading-relaxed text-text-secondary">{pulse.summary}</p>
      )}

      {note && <p className="mt-2 text-[11px] italic text-text-secondary/80">{note}</p>}
    </div>
  );
}

function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={spinning ? "animate-spin" : ""}
      aria-hidden
    >
      <path d="M21 12a9 9 0 1 1-3-6.7" />
      <path d="M21 3v5h-5" />
    </svg>
  );
}
