"use client";

import { useEffect, useState } from "react";

import { getBreakingAlerts, type BreakingAlertItem } from "@/lib/apiClient";
import { relTime } from "@/lib/time";

/**
 * In-app history of breaking-news alerts (the ones that fired a push). A push is fleeting — this is
 * where you review what market-moving news Argus flagged today, even if you missed the notification.
 * Hidden entirely when there's nothing recent, so it only shows up when it matters.
 */
export function BreakingAlerts() {
  const [alerts, setAlerts] = useState<BreakingAlertItem[] | null>(null);

  useEffect(() => {
    let active = true;
    getBreakingAlerts()
      .then((a) => active && setAlerts(a))
      .catch(() => active && setAlerts([]));
    return () => {
      active = false;
    };
  }, []);

  // Nothing to show (still loading, failed, or no recent alerts) → render nothing.
  if (!alerts || alerts.length === 0) return null;

  return (
    <section className="glass relative overflow-hidden rounded-2xl p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-text-primary">
          <span aria-hidden>⚠️</span> Breaking alerts
          <span className="font-mono text-xs text-text-secondary">{alerts.length}</span>
        </h3>
        <span className="text-[11px] text-text-secondary">market-moving news pushed to your phone</span>
      </div>

      <ul className="mt-3 flex flex-col divide-y divide-[var(--hairline)]">
        {alerts.map((a) => (
          <li key={a.id} className="py-2.5">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm leading-snug text-text-primary">
                  {a.url ? (
                    <a
                      href={a.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="underline-offset-2 hover:underline"
                    >
                      {a.headline}
                    </a>
                  ) : (
                    a.headline
                  )}
                </p>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-text-secondary">
                  <span className="rounded bg-warning/15 px-1.5 py-0.5 font-medium text-warning">{a.reason}</span>
                  {a.tickers.slice(0, 4).map((t) => (
                    <span key={t} className="font-mono text-accent">
                      {t}
                    </span>
                  ))}
                </div>
              </div>
              <time
                dateTime={a.createdAt}
                className="shrink-0 font-mono text-[10px] text-text-secondary"
                title={a.createdAt}
              >
                {relTime(a.createdAt)}
              </time>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
