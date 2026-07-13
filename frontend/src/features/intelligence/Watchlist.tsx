"use client";

import { useEffect, useState } from "react";

import {
  addWatchlist,
  discoverWatchlist,
  getWatchlist,
  removeWatchlist,
  type WatchlistEntry,
} from "@/lib/apiClient";

/**
 * Watchlist — the universe beyond your holdings. Adding a ticker widens what the agents cover, so
 * Agent 5 starts recommending on it alongside your portfolio (from the next agent cycle). Entries you
 * add are MANUAL; DISCOVERED ones come from the auto-discovery agent.
 */
export function Watchlist() {
  const [entries, setEntries] = useState<WatchlistEntry[] | null>(null);
  const [ticker, setTicker] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [discovering, setDiscovering] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      setEntries(await getWatchlist());
    } catch {
      setEntries([]);
    }
  }

  useEffect(() => {
    let active = true;
    getWatchlist()
      .then((e) => active && setEntries(e))
      .catch(() => active && setEntries([]));
    return () => {
      active = false;
    };
  }, []);

  async function onAdd() {
    const t = ticker.trim().toUpperCase();
    if (!t || busy) return;
    setBusy(true);
    setError(null);
    try {
      await addWatchlist(t, note.trim() || undefined);
      setTicker("");
      setNote("");
      await refresh();
    } catch {
      setError("Couldn't add that ticker.");
    } finally {
      setBusy(false);
    }
  }

  async function onRemove(t: string) {
    try {
      await removeWatchlist(t);
      await refresh();
    } catch {
      setError("Couldn't remove that ticker.");
    }
  }

  async function onDiscover() {
    if (discovering) return;
    setDiscovering(true);
    setError(null);
    try {
      setEntries(await discoverWatchlist());
    } catch {
      setError("Couldn't run discovery just now.");
    } finally {
      setDiscovering(false);
    }
  }

  return (
    <section className="glass relative overflow-hidden rounded-2xl p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-text-primary">
          Watchlist · beyond your portfolio
          {entries && entries.length > 0 && (
            <span className="ml-2 font-mono text-xs text-text-secondary">{entries.length}</span>
          )}
        </h3>
        <button
          type="button"
          onClick={onDiscover}
          disabled={discovering}
          className="shrink-0 rounded-lg border border-[var(--hairline)] px-2.5 py-1 text-[11px] font-semibold text-text-primary transition hover:bg-border/20 disabled:opacity-60"
          title="Promote tickers the market is buzzing about that you don't already hold or watch"
        >
          {discovering ? "Scanning…" : "✨ Find trending"}
        </button>
      </div>
      <p className="mt-0.5 text-xs text-text-secondary">
        Add tickers you want covered — the agents ingest them and Agent 5 recommends on them from the next
        cycle (holdings stay untouched).
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input
          value={ticker}
          onChange={(e) => setTicker(e.target.value.toUpperCase())}
          onKeyDown={(e) => e.key === "Enter" && onAdd()}
          placeholder="Ticker (e.g. NVDA)"
          maxLength={12}
          className="w-32 rounded-lg border border-[var(--hairline)] bg-transparent px-2.5 py-1.5 font-mono text-sm uppercase text-text-primary outline-none focus:border-accent"
        />
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && onAdd()}
          placeholder="Note (optional)"
          maxLength={120}
          className="min-w-0 flex-1 rounded-lg border border-[var(--hairline)] bg-transparent px-2.5 py-1.5 text-sm text-text-primary outline-none focus:border-accent"
        />
        <button
          type="button"
          onClick={onAdd}
          disabled={busy || !ticker.trim()}
          className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Adding…" : "Add"}
        </button>
      </div>

      {error && <p className="mt-2 text-[11px] italic text-losses">{error}</p>}

      {entries === null ? (
        <p className="mt-3 text-xs text-text-secondary">Loading…</p>
      ) : entries.length === 0 ? (
        <p className="mt-3 text-xs text-text-secondary">No watchlist tickers yet — add one above.</p>
      ) : (
        <ul className="mt-3 flex flex-wrap gap-2">
          {entries.map((e) => (
            <li
              key={e.ticker}
              className="flex items-center gap-2 rounded-lg border border-[var(--hairline)] bg-border/[0.15] px-2.5 py-1.5"
              title={e.note ?? undefined}
            >
              <span className="font-mono text-sm font-semibold text-text-primary">{e.ticker}</span>
              {e.source === "DISCOVERED" && (
                <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-accent">
                  discovered
                </span>
              )}
              <button
                type="button"
                onClick={() => onRemove(e.ticker)}
                aria-label={`Remove ${e.ticker}`}
                className="text-text-secondary transition hover:text-losses"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
