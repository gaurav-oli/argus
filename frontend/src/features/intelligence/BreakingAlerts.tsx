"use client";

import { useCallback, useEffect, useState } from "react";

import { ArticleTags } from "@/components/ui/ArticleTags";
import { Carousel } from "@/components/ui/Carousel";
import { RefreshButton } from "@/components/ui/RefreshIcon";
import { SummaryBlock } from "@/components/ui/SummaryBlock";
import { getBreakingAlerts, markBreakingDone, type BreakingAlertItem } from "@/lib/apiClient";
import { absTime, relTime } from "@/lib/time";

/**
 * In-app breaking-news carousel — market-moving stories that fired a push, browsable rather than a
 * flat list of one-liners. Before it's shown, Gemma checks the alert against recently-covered
 * headlines (different wording/source, same underlying event never gets its own card) and, if it's
 * genuinely new, writes a beginner-friendly explanation. "Done Reading" is a soft dismiss — the row
 * (and the fact it fired a push) stays in the audit trail server-side; it just leaves the carousel.
 * Hidden entirely when there's nothing to show, so it only appears when it matters.
 */

const POLL_MS = 15_000;

export function BreakingAlerts() {
  const [alerts, setAlerts] = useState<BreakingAlertItem[] | null | undefined>(undefined);
  const [pending, setPending] = useState(0);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(() => {
    return getBreakingAlerts()
      .then((q) => {
        setAlerts(q.alerts);
        setPending(q.pending);
      })
      .catch(() => setAlerts((prev) => prev ?? null));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await load();
    } finally {
      setRefreshing(false);
    }
  }, [load]);

  const waiting = alerts !== undefined && alerts !== null && alerts.length === 0 && pending > 0;
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [waiting, load]);

  const onDone = useCallback(async (id: number) => {
    setBusyId(id);
    try {
      await markBreakingDone(id);
      setAlerts((prev) => (prev ? prev.filter((a) => a.id !== id) : prev));
    } finally {
      setBusyId(null);
    }
  }, []);

  // Nothing to show (still loading, failed, or none ready/pending) → render nothing, same as before.
  if (!alerts || (alerts.length === 0 && pending === 0)) return null;

  return (
    <section className="glass relative overflow-hidden rounded-2xl p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-text-primary">
          <span aria-hidden>⚠️</span> Breaking alerts
          {alerts.length > 0 && <span className="font-mono text-xs text-text-secondary">{alerts.length}</span>}
        </h3>
        <div className="flex items-center gap-2">
          <span className="text-[11px] text-text-secondary">
            market-moving news pushed to your phone
            {pending > 0 && <span className="ml-1 text-text-secondary/70">· {pending} more on the way</span>}
          </span>
          <RefreshButton onClick={onRefresh} refreshing={refreshing} />
        </div>
      </div>

      {alerts.length === 0 ? (
        <div className="mt-3 flex items-center gap-2 py-2">
          <span className="h-3 w-3 animate-spin rounded-full border-2 border-accent/30 border-t-accent" aria-hidden />
          <p className="text-sm text-text-secondary">Checking for duplicates and preparing summaries…</p>
        </div>
      ) : (
        <div className="mt-3">
          <Carousel
            items={alerts}
            keyOf={(a) => a.id}
            itemWidth="min(92vw, 560px)"
            renderItem={(a) => <AlertCard alert={a} busy={busyId === a.id} onDone={() => onDone(a.id)} />}
          />
        </div>
      )}
    </section>
  );
}

function AlertCard({ alert, busy, onDone }: { alert: BreakingAlertItem; busy: boolean; onDone: () => void }) {
  return (
    <article className="flex h-full flex-col rounded-xl border border-border bg-surface p-4">
      <div className="flex items-start justify-between gap-3">
        <span className="rounded bg-warning/15 px-1.5 py-0.5 text-[11px] font-medium text-warning">
          {alert.reason}
        </span>
        <div className="shrink-0 whitespace-nowrap text-right text-[10px] text-text-secondary">
          <time dateTime={alert.createdAt} className="font-mono">
            {absTime(alert.createdAt)}
          </time>
          <span className="ml-1 text-text-secondary/70">({relTime(alert.createdAt)})</span>
        </div>
      </div>

      <h4 className="mt-1.5 font-display text-lg font-semibold leading-snug text-text-primary">
        {alert.headline}
      </h4>

      <SummaryBlock text={alert.summary} />

      <ArticleTags tickers={alert.tickers} />

      <div className="mt-auto flex items-center justify-between gap-3 pt-4">
        {alert.url ? (
          <a
            href={alert.url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[11px] font-medium text-text-secondary underline-offset-2 hover:text-text-primary hover:underline"
          >
            Read full article ↗
          </a>
        ) : (
          <span />
        )}

        <button
          type="button"
          onClick={onDone}
          disabled={busy}
          className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white transition hover:opacity-90 disabled:opacity-60"
        >
          {busy ? "Saving…" : "Done Reading"}
          {!busy && (
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M20 6 9 17l-5-5" />
            </svg>
          )}
        </button>
      </div>
    </article>
  );
}
