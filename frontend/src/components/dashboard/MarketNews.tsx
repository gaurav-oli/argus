"use client";

import { useCallback, useEffect, useState } from "react";

import { Carousel } from "@/components/ui/Carousel";
import { SummaryBlock } from "@/components/ui/SummaryBlock";
import { getNewsQueue, markNewsDone, type NewsCardItem } from "@/lib/apiClient";
import { absTime, relTime } from "@/lib/time";

/**
 * Curated market news — a browsable carousel of the important, recent stories Argus fetched (not
 * just your holdings, never older than ~1 day), each with a longer Gemma-written explanation: what
 * happened, why it matters, and the likely market impact, in plain everyday language, plus a
 * beginner glossary for any jargon. "Done Reading" removes a card from the queue; the carousel
 * settles on its neighbor. A badge shows how many more are queued and how many are still being
 * written.
 *
 * Summaries are produced in the background, so an empty queue with cards still pending polls until
 * the first one lands. `undefined` = first load, `null` = load failed.
 */

const POLL_MS = 15_000;

export function MarketNews() {
  const [cards, setCards] = useState<NewsCardItem[] | null | undefined>(undefined);
  const [pending, setPending] = useState(0);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(() => {
    getNewsQueue()
      .then((q) => {
        setCards(q.cards);
        setPending(q.pending);
      })
      .catch(() => setCards((prev) => prev ?? null));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // While nothing is ready but summaries are still being written, poll until one lands.
  const waiting = cards !== undefined && cards !== null && cards.length === 0 && pending > 0;
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [waiting, load]);

  const onDone = useCallback(async (id: number) => {
    setBusyId(id);
    setNote(null);
    try {
      await markNewsDone(id);
      setCards((prev) => (prev ? prev.filter((c) => c.id !== id) : prev));
    } catch {
      setNote("Couldn't update just now — try again in a moment.");
    } finally {
      setBusyId(null);
    }
  }, []);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Market News</h3>
        <QueueBadge count={cards?.length ?? 0} pending={pending} />
      </div>

      {cards === undefined ? (
        <Skeleton />
      ) : cards === null ? (
        <p className="mt-3 py-2 text-sm text-text-secondary">News is unavailable right now.</p>
      ) : cards.length === 0 ? (
        <EmptyState pending={pending} />
      ) : (
        <div className="mt-3">
          <Carousel
            items={cards}
            keyOf={(c) => c.id}
            itemWidth="min(92vw, 560px)"
            renderItem={(c) => <NewsCard card={c} busy={busyId === c.id} onDone={() => onDone(c.id)} />}
          />
          {note && <p className="mt-2 text-[11px] italic text-text-secondary/80">{note}</p>}
        </div>
      )}
    </div>
  );
}

function NewsCard({ card, busy, onDone }: { card: NewsCardItem; busy: boolean; onDone: () => void }) {
  return (
    <article className="flex h-full flex-col rounded-xl border border-border bg-surface p-4">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[11px] text-text-secondary">
        <span className="font-medium uppercase tracking-wide text-accent">{card.source}</span>
        <span aria-hidden>·</span>
        {/* News date, shown prominently — cards are never older than ~1 day. */}
        <time dateTime={card.publishedAt} className="font-mono">
          {absTime(card.publishedAt)}
        </time>
        <span className="text-text-secondary/70">({relTime(card.publishedAt)})</span>
      </div>

      <h4 className="mt-1.5 font-display text-lg font-semibold leading-snug text-text-primary">
        {card.headline}
      </h4>

      <SummaryBlock text={card.summary} />

      {card.tickers.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {card.tickers.map((t) => (
            <span
              key={t}
              className="rounded-md bg-accent/[0.08] px-1.5 py-0.5 font-mono text-[10px] font-medium text-accent"
            >
              {t}
            </span>
          ))}
        </div>
      )}

      <div className="mt-auto flex items-center justify-between gap-3 pt-4">
        {card.url ? (
          <a
            href={card.url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[11px] font-medium text-text-secondary underline-offset-2 hover:text-text-primary hover:underline"
          >
            Read full article ↗
          </a>
        ) : (
          <span className="text-[11px] text-text-secondary/60">Fetched {relTime(card.fetchedAt)}</span>
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

function QueueBadge({ count, pending }: { count: number; pending: number }) {
  if (count === 0 && pending === 0) return null;
  return (
    <span className="flex items-center gap-1 font-mono text-[10px] text-text-secondary">
      <span className="rounded-full bg-accent/[0.12] px-2 py-0.5 font-semibold text-accent">
        {count} in queue
      </span>
      {pending > 0 && <span className="text-text-secondary/70">+{pending} on the way</span>}
    </span>
  );
}

function EmptyState({ pending }: { pending: number }) {
  if (pending > 0) {
    return (
      <div className="mt-3 flex items-center gap-2 py-2">
        <span className="h-3 w-3 animate-spin rounded-full border-2 border-accent/30 border-t-accent" aria-hidden />
        <p className="text-sm text-text-secondary">Preparing summaries for the latest news…</p>
      </div>
    );
  }
  return (
    <div className="mt-3 flex items-center gap-2 py-2">
      <span className="text-base">✅</span>
      <p className="text-sm text-text-secondary">You&apos;re all caught up — no important news right now.</p>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="mt-3 space-y-2">
      <div className="h-3 w-1/3 animate-pulse rounded bg-border/40" />
      <div className="h-5 w-3/4 animate-pulse rounded-lg bg-border/40" />
      <div className="h-4 w-full animate-pulse rounded-lg bg-border/40" />
      <div className="h-4 w-5/6 animate-pulse rounded-lg bg-border/40" />
    </div>
  );
}
