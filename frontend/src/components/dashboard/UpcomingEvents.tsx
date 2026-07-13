"use client";

import { getUpcomingEvents, type UpcomingEvent } from "@/lib/apiClient";
import { Skeleton } from "@/components/ui/Skeleton";
import { useEffect, useState } from "react";

/**
 * Upcoming economic-calendar events (Epic 5 — Agent 7). Real data from /api/calendar/upcoming:
 * earnings, Fed, and other events soonest-first, with the pre-earnings quiet-period flag (Story 5.3)
 * shown on earnings that are imminent.
 */
export function UpcomingEvents() {
  const [events, setEvents] = useState<UpcomingEvent[] | null>(null);

  useEffect(() => {
    let active = true;
    getUpcomingEvents()
      .then((e) => active && setEvents(e))
      .catch(() => active && setEvents([]));
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="flex h-full flex-col">
      <h3 className="mb-3 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
        Upcoming events
      </h3>
      {events === null ? (
        <div className="space-y-3">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      ) : events.length === 0 ? (
        <p className="text-sm text-text-secondary">No events in the next two weeks.</p>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {events.map((e) => (
            <li key={e.id} className="flex items-center gap-3">
              <span className="flex w-12 flex-col items-center rounded bg-border/50 px-1 py-0.5 leading-tight">
                <span className="text-sm font-bold tabular-nums text-text-primary">{e.daysUntil}</span>
                <span className="text-[9px] uppercase text-text-secondary">days</span>
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm text-text-primary">
                  {e.ticker && <span className="font-semibold">{e.ticker} </span>}
                  {e.title}
                </p>
                <p className="flex items-center gap-2 text-xs text-text-secondary">
                  <span className="uppercase tracking-wide">{label(e.type)}</span>
                  {e.type === "IPO" && (
                    <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[10px] font-medium text-accent">
                      new listing
                    </span>
                  )}
                  <QuietBadge status={e.quietPeriod} />
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function QuietBadge({ status }: { status: string | null }) {
  if (status === "QUIET") {
    return (
      <span className="rounded bg-losses/15 px-1.5 py-0.5 text-[10px] font-medium text-losses">
        earnings ahead
      </span>
    );
  }
  if (status === "NOTE") {
    return (
      <span className="rounded bg-warning/15 px-1.5 py-0.5 text-[10px] font-medium text-warning">
        earnings soon
      </span>
    );
  }
  return null;
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
