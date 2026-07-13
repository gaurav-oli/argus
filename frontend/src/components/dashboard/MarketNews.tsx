"use client";

import { useCallback, useEffect, useState } from "react";

import { getNextNews, markNewsDone, type NewsFeed } from "@/lib/apiClient";
import { absTime, relTime } from "@/lib/time";

/**
 * Curated market news — the dashboard's "one story at a time" reader. Argus promotes the important,
 * recent articles it fetched (not just your holdings, and never older than ~1 day) and, with the local
 * model, writes a paragraph on what happened and its market impact. You read one card, hit "Done
 * Reading" to delete it, and the next highest-impact card appears. A badge shows how many are queued.
 *
 * Summaries are produced in the background, so if a card isn't ready yet the section polls until one
 * lands. `undefined` = first load, `null` = load failed.
 */

const POLL_MS = 15_000;

export function MarketNews() {
  const [feed, setFeed] = useState<NewsFeed | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getNextNews()
      .then((next) => active && setFeed(next))
      .catch(() => active && setFeed((prev) => prev ?? null));
    return () => {
      active = false;
    };
  }, []);

  // While nothing is ready but summaries are still being written, poll until one lands.
  const waiting = feed !== undefined && feed !== null && feed.card === null && feed.pending > 0;
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(() => {
      getNextNews()
        .then(setFeed)
        .catch(() => {});
    }, POLL_MS);
    return () => clearInterval(t);
  }, [waiting]);

  const onDone = useCallback(async () => {
    if (busy || !feed?.card) return;
    setBusy(true);
    setNote(null);
    try {
      setFeed(await markNewsDone(feed.card.id));
    } catch {
      setNote("Couldn't update just now — try again in a moment.");
    } finally {
      setBusy(false);
    }
  }, [busy, feed]);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Market News</h3>
        <QueueBadge feed={feed} />
      </div>

      {feed === undefined ? (
        <Skeleton />
      ) : feed === null ? (
        <p className="mt-3 py-2 text-sm text-text-secondary">News is unavailable right now.</p>
      ) : feed.card === null ? (
        <EmptyState pending={feed.pending} />
      ) : (
        <article className="mt-3">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[11px] text-text-secondary">
            <span className="font-medium uppercase tracking-wide text-accent">{feed.card.source}</span>
            <span aria-hidden>·</span>
            {/* News date, shown prominently — cards are never older than ~1 day. */}
            <time dateTime={feed.card.publishedAt} className="font-mono">
              {absTime(feed.card.publishedAt)}
            </time>
            <span className="text-text-secondary/70">({relTime(feed.card.publishedAt)})</span>
          </div>

          <h4 className="mt-1.5 font-display text-lg font-semibold leading-snug text-text-primary">
            {feed.card.headline}
          </h4>

          <Summary text={feed.card.summary} />

          {feed.card.tickers.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {feed.card.tickers.map((t) => (
                <span
                  key={t}
                  className="rounded-md bg-accent/[0.08] px-1.5 py-0.5 font-mono text-[10px] font-medium text-accent"
                >
                  {t}
                </span>
              ))}
            </div>
          )}

          <div className="mt-4 flex items-center justify-between gap-3">
            {feed.card.url ? (
              <a
                href={feed.card.url}
                target="_blank"
                rel="noopener noreferrer"
                className="text-[11px] font-medium text-text-secondary underline-offset-2 hover:text-text-primary hover:underline"
              >
                Read full article ↗
              </a>
            ) : (
              <span className="text-[11px] text-text-secondary/60">Fetched {relTime(feed.card.fetchedAt)}</span>
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

          {note && <p className="mt-2 text-[11px] italic text-text-secondary/80">{note}</p>}
        </article>
      )}
    </div>
  );
}

/** Split "<paragraph> … KEY TERMS: <term — def> …" into the plain paragraph and a beginner glossary. */
function parseSummary(raw: string): { paragraph: string; terms: { term: string; def: string }[] } {
  const marker = raw.search(/key\s*terms\s*:/i);
  if (marker === -1) return { paragraph: raw.trim(), terms: [] };
  const paragraph = raw.slice(0, marker).trim();
  const rest = raw.slice(marker).replace(/^key\s*terms\s*:/i, "").trim();
  if (/^none\.?$/i.test(rest)) return { paragraph, terms: [] };
  const terms = rest
    .split(/\n+/)
    .map((line) => line.replace(/^\s*[-*•\d.]+\s*/, "").trim())
    .filter((line) => line.length > 0 && !/^none\.?$/i.test(line))
    .map((line) => {
      const parts = line.split(/\s+[—–-]\s+/);
      const term = parts[0].trim();
      const def = line.slice(term.length).replace(/^\s*[—–-]\s*/, "").trim();
      return { term, def };
    })
    .filter((t) => t.term.length > 0);
  return { paragraph, terms };
}

function Summary({ text }: { text: string }) {
  const { paragraph, terms } = parseSummary(text);
  return (
    <>
      <p className="mt-2 text-sm leading-relaxed text-text-secondary">{paragraph}</p>
      {terms.length > 0 && (
        <div className="mt-3 rounded-lg border border-border/50 bg-border/[0.15] p-3">
          <h5 className="text-[10px] font-semibold uppercase tracking-wider text-text-secondary">
            📘 Key terms
          </h5>
          <dl className="mt-1.5 space-y-1">
            {terms.map((t) => (
              <div key={t.term} className="text-xs leading-relaxed">
                <dt className="inline font-semibold text-text-primary">{t.term}</dt>
                {t.def && <dd className="inline text-text-secondary"> — {t.def}</dd>}
              </div>
            ))}
          </dl>
        </div>
      )}
    </>
  );
}

function QueueBadge({ feed }: { feed: NewsFeed | null | undefined }) {
  if (!feed) return null;
  const { remaining, pending } = feed;
  if (remaining === 0 && pending === 0) return null;
  return (
    <span className="flex items-center gap-1 font-mono text-[10px] text-text-secondary">
      <span className="rounded-full bg-accent/[0.12] px-2 py-0.5 font-semibold text-accent">
        {remaining} in queue
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
