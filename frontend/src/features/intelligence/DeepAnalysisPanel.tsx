"use client";

import {
  getDeepAnalyses,
  getDeepQueue,
  runDeepAnalysis,
  type DeepAnalysisView,
  type DeepQueue,
  type DeepVerdictName,
} from "@/lib/apiClient";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Skeleton } from "@/components/ui/Skeleton";
import { useCallback, useEffect, useState } from "react";

const VERDICT_STYLE: Record<DeepVerdictName, { label: string; cls: string; icon: string }> = {
  WORTH_BUYING: { label: "Worth buying", cls: "bg-gains/15 text-gains", icon: "✓" },
  WAIT: { label: "Wait — not yet", cls: "bg-warning/15 text-warning", icon: "…" },
  NOT_WORTH_BUYING: { label: "Not worth buying", cls: "bg-losses/15 text-losses", icon: "✕" },
};

function holdText(days: number | null): string | null {
  if (days == null) return null;
  const term = days <= 7 ? "short-term" : days <= 30 ? "medium-term" : "long-term";
  return `hold ~${days} days · ${term}`;
}

function ageText(iso: string | null): string {
  if (!iso) return "";
  const hours = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 3_600_000));
  return hours < 24 ? `${hours}h ago` : `${Math.round(hours / 24)}d ago`;
}

/**
 * Agent 11 — the deep analyst's verdicts. Each stock gets a slow, multi-stage analysis (every other agent's
 * evidence, the chart, the fundamentals, four specialist analysts, a skeptic and a portfolio manager) ending in
 * a plain answer: worth buying, wait, or not worth buying — and for a buy, how long to hold. It runs nightly and
 * on demand; a full pass legitimately takes hours, so progress shows here while it works.
 */
export function DeepAnalysisPanel() {
  const [items, setItems] = useState<DeepAnalysisView[] | null>(null);
  const [queue, setQueue] = useState<DeepQueue | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [ticker, setTicker] = useState("");
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    getDeepAnalyses().then(setItems).catch(() => setItems((cur) => cur ?? []));
    getDeepQueue().then(setQueue).catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Poll only while the analyst has work — a full pass takes hours, an idle page shouldn't hammer the API.
  const busy = queue != null && (queue.running != null || queue.queued > 0);
  useEffect(() => {
    if (!busy) return;
    const id = setInterval(refresh, 10_000);
    return () => clearInterval(id);
  }, [busy, refresh]);

  async function analyze(t: string) {
    const clean = t.trim().toUpperCase();
    if (!clean) return;
    setError(null);
    try {
      await runDeepAnalysis(clean);
      setTicker("");
      refresh();
    } catch {
      setError(`Couldn't queue ${clean} — is that a valid ticker?`);
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Deep analysis · Agent 11</h2>
          <p className="mt-1 max-w-xl text-xs text-text-secondary">
            Takes its time: engages every other agent, reads the chart and the financials, argues both sides, then answers whether a stock is
            worth buying — and for how long to hold it.
          </p>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void analyze(ticker);
          }}
          className="flex gap-2"
        >
          <input
            value={ticker}
            onChange={(e) => setTicker(e.target.value)}
            placeholder="Ticker"
            maxLength={8}
            className="w-24 rounded border border-border bg-background px-2 py-1 text-xs uppercase text-text-primary"
          />
          <button type="submit" className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent hover:bg-accent/10">
            Analyze
          </button>
        </form>
      </div>

      {error && <p className="text-xs text-losses">{error}</p>}
      {queue && (queue.running || queue.queued > 0) && (
        <p className="rounded-lg bg-accent/10 px-3 py-2 text-xs text-accent">
          ⏳ Working: {queue.running ?? "starting next"} · {queue.queued} more queued
        </p>
      )}

      {items === null ? (
        <Skeleton className="h-24 w-full" />
      ) : items.length === 0 ? (
        <p className="text-sm text-text-secondary">
          No analyses yet. The first pass over your holdings starts automatically and can take a few hours; you can also analyze any ticker above.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((a) => {
            const v = a.verdict ? VERDICT_STYLE[a.verdict] : null;
            const isOpen = open === a.ticker;
            return (
              <li key={a.id} className="rounded-lg border border-border">
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? null : a.ticker)}
                  className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-left"
                >
                  <span className="w-14 shrink-0 text-sm font-bold text-text-primary">
                    <Sensitive>{a.ticker}</Sensitive>
                  </span>
                  {v && <span className={`rounded px-2 py-0.5 text-[11px] font-semibold ${v.cls}`}>{v.icon} {v.label}</span>}
                  {a.verdict === "WORTH_BUYING" && a.holdDays != null && (
                    <span className="text-xs font-medium text-accent">⏱ {holdText(a.holdDays)}</span>
                  )}
                  {a.conviction != null && <span className="text-xs tabular-nums text-text-secondary">conviction {a.conviction}/100</span>}
                  <span className="min-w-0 flex-1 truncate text-xs text-text-secondary">{a.headline}</span>
                  {a.stale && <span className="text-[10px] text-warning">stale</span>}
                  {a.inProgress && <span className="text-[10px] text-accent">re-analysing: {a.inProgress.stage ?? a.inProgress.status.toLowerCase()}</span>}
                  <span className="text-[10px] text-text-secondary">{ageText(a.finishedAt)}</span>
                </button>
                {isOpen && <Detail a={a} onRerun={() => void analyze(a.ticker)} />}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-text-secondary">{title}</p>
      <div className="whitespace-pre-line text-xs leading-relaxed text-text-primary">{children}</div>
    </div>
  );
}

function List({ items }: { items: string[] }) {
  return (
    <ul className="flex flex-col gap-0.5">
      {items.map((x, i) => (
        <li key={i}>• {x}</li>
      ))}
    </ul>
  );
}

function Detail({ a, onRerun }: { a: DeepAnalysisView; onRerun: () => void }) {
  return (
    <div className="flex flex-col gap-3 border-t border-border px-3 py-3">
      {a.thesis && <Block title="Thesis">{a.thesis}</Block>}
      <div className="grid gap-3 sm:grid-cols-2">
        {a.bullCase && <Block title="Best case for owning it">{a.bullCase}</Block>}
        {a.bearCase && <Block title="Best case against">{a.bearCase}</Block>}
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {a.risks.length > 0 && <Block title="Risks"><List items={a.risks} /></Block>}
        {a.catalysts.length > 0 && <Block title="Catalysts to watch"><List items={a.catalysts} /></Block>}
      </div>
      {a.invalidation && <Block title="What would change its mind">{a.invalidation}</Block>}
      {a.guardNotes.length > 0 && (
        <div className="rounded-lg bg-warning/10 px-3 py-2">
          <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-warning">Guardrails applied to the model&apos;s verdict</p>
          <List items={a.guardNotes} />
        </div>
      )}
      <details className="rounded-lg border border-border px-3 py-2">
        <summary className="cursor-pointer text-[11px] text-text-secondary">The specialists&apos; reasoning</summary>
        <div className="mt-2 flex flex-col gap-3">
          {a.technicalSummary && <Block title={`Chart technician${a.technicalScore != null ? ` · deterministic score ${a.technicalScore}` : ""}`}>{a.technicalSummary}</Block>}
          {a.fundamentalSummary && <Block title={`Fundamental analyst${a.fundamentalScore != null ? ` · deterministic score ${a.fundamentalScore}` : ""}`}>{a.fundamentalSummary}</Block>}
          {a.catalystSummary && <Block title="News & catalyst analyst">{a.catalystSummary}</Block>}
          {a.macroSummary && <Block title="Macro & sector strategist">{a.macroSummary}</Block>}
          {a.skepticView && <Block title="Skeptic">{a.skepticView}</Block>}
        </div>
      </details>
      <div className="flex flex-wrap items-center justify-between gap-2 text-[10px] text-text-secondary">
        <span>{a.model} · consensus {a.consensusScore ?? "–"}</span>
        <button type="button" onClick={onRerun} className="rounded border border-accent/40 px-2 py-1 font-medium text-accent hover:bg-accent/10">
          Re-analyze now
        </button>
      </div>
    </div>
  );
}
