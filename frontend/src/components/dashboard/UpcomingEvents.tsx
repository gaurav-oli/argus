"use client";

import { useEffect, useMemo, useState } from "react";
import { getUpcomingEvents, type UpcomingEvent } from "@/lib/apiClient";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Skeleton } from "@/components/ui/Skeleton";
import { SortToggle } from "@/components/ui/SortToggle";

type SortMode = "newest" | "oldest" | "soonest";
const SORT_OPTIONS = [
  { value: "newest", label: "Newest" },
  { value: "oldest", label: "Oldest" },
  { value: "soonest", label: "Soonest" },
] as const satisfies readonly { value: SortMode; label: string }[];

function sortEvents(events: UpcomingEvent[], mode: SortMode): UpcomingEvent[] {
  const sorted = [...events];
  if (mode === "oldest") {
    sorted.sort((a, b) => a.eventDate.localeCompare(b.eventDate));
  } else if (mode === "soonest") {
    // Closest to today first regardless of past/future — a genuinely different axis than
    // newest/oldest since this list spans both directions from today.
    sorted.sort((a, b) => Math.abs(a.daysUntil) - Math.abs(b.daysUntil));
  } else {
    sorted.sort((a, b) => b.eventDate.localeCompare(a.eventDate));
  }
  return sorted;
}

/**
 * Economic-calendar events (Epic 5 — Agent 7). Real data from /api/calendar/upcoming, latest to
 * oldest by default: forward-looking earnings/Fed/other events plus earnings reported in the last
 * 30 days. Upcoming earnings carry the pre-earnings quiet-period flag (Story 5.3); reported
 * earnings carry a beat/miss badge from the actual-vs-estimate EPS surprise. A compact,
 * rule-separated list — one row per event with a small company icon — in place of the earlier
 * boxed "days" counter, which ran two lines tall per item and dominated the page for more than a
 * handful of events. Sort order (Newest/Oldest/Soonest) is client-side over the same fetched set.
 */
export function UpcomingEvents() {
  const [events, setEvents] = useState<UpcomingEvent[] | null>(null);
  const [sortMode, setSortMode] = useState<SortMode>("newest");

  useEffect(() => {
    let active = true;
    getUpcomingEvents()
      .then((e) => active && setEvents(e))
      .catch(() => active && setEvents([]));
    return () => {
      active = false;
    };
  }, []);

  const sorted = useMemo(() => (events ? sortEvents(events, sortMode) : []), [events, sortMode]);

  return (
    <div className="flex h-full flex-col">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Upcoming events
        </h3>
        {events != null && events.length > 0 && (
          <SortToggle options={SORT_OPTIONS} value={sortMode} onChange={setSortMode} />
        )}
      </div>
      {events === null ? (
        <div className="space-y-3">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      ) : events.length === 0 ? (
        <p className="text-sm text-text-secondary">No events in the last 30 days or next two weeks.</p>
      ) : (
        <ul className="flex flex-col">
          {sorted.map((e) => {
            const past = e.daysUntil < 0;
            return (
              <li
                key={e.id}
                className="flex items-start gap-2.5 border-b border-[var(--hairline)] py-2.5 last:border-b-0"
              >
                <CompanyIcon ticker={e.ticker} logoUrl={e.logoUrl} title={e.title} className="mt-0.5" />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <p className="truncate text-sm text-text-primary">
                      {e.ticker && <span className="font-semibold">{e.ticker} </span>}
                      {e.title}
                    </p>
                    <span className="shrink-0 text-[11px] text-text-secondary">{when(e.daysUntil)}</span>
                  </div>
                  <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-xs text-text-secondary">
                    <span className="uppercase tracking-wide">{label(e.type)}</span>
                    {e.type === "IPO" && <span className="text-accent">· new listing</span>}
                    <QuietBadge status={e.quietPeriod} />
                    <BeatMissBadge surprisePercent={e.epsSurprisePercent} />
                    {past && e.epsSurprisePercent == null && <span>· reported</span>}
                  </p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function QuietBadge({ status }: { status: string | null }) {
  if (status === "QUIET") {
    return <span className="text-losses">· earnings ahead</span>;
  }
  if (status === "NOTE") {
    return <span className="text-warning">· earnings soon</span>;
  }
  return null;
}

function BeatMissBadge({ surprisePercent }: { surprisePercent: number | null }) {
  if (surprisePercent == null) return null;
  const beat = surprisePercent >= 0;
  return (
    <span style={{ color: beat ? "var(--color-gains)" : "var(--color-losses)" }}>
      · {beat ? "Beat" : "Miss"} {beat ? "+" : ""}
      {surprisePercent.toFixed(1)}%
    </span>
  );
}

/** "in 7d" / "tomorrow" / "today" / "2d ago" — compact right-aligned time label. */
function when(daysUntil: number): string {
  if (daysUntil === 0) return "today";
  if (daysUntil === 1) return "tomorrow";
  if (daysUntil === -1) return "yesterday";
  return daysUntil > 0 ? `in ${daysUntil}d` : `${Math.abs(daysUntil)}d ago`;
}

function label(type: string): string {
  switch (type) {
    case "EARNINGS":
      return "Earnings";
    case "FED":
      return "Fed";
    case "EX_DIVIDEND":
      return "Ex-dividend";
    case "LOCKUP":
      return "Lock-up";
    case "IPO":
      return "IPO";
    default:
      return type;
  }
}
