"use client";

import {
  ApiError,
  getDeepAnalysisFor,
  runDeepAnalysis,
  type DeepAnalysisView,
  type DeepTickerView,
  type DeepVerdictName,
} from "@/lib/apiClient";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Skeleton } from "@/components/ui/Skeleton";
import { useCallback, useEffect, useState } from "react";

export const VERDICT_STYLE: Record<DeepVerdictName, { label: string; cls: string; icon: string }> = {
  WORTH_BUYING: { label: "Worth buying", cls: "bg-gains/15 text-gains", icon: "✓" },
  WAIT: { label: "Wait — not yet", cls: "bg-warning/15 text-warning", icon: "…" },
  NOT_WORTH_BUYING: { label: "Not worth buying", cls: "bg-losses/15 text-losses", icon: "✕" },
};

export function holdText(days: number | null): string | null {
  if (days == null) return null;
  const term = days <= 7 ? "short-term" : days <= 30 ? "medium-term" : "long-term";
  return `hold ~${days} days · ${term}`;
}

/** More decimals for a sub-$1 stock, so a real level doesn't display as a misleading $0.00. */
function money(n: number): string {
  const digits = n >= 1 ? 2 : n >= 0.01 ? 4 : 6;
  return `$${n.toFixed(digits)}`;
}

export function ageText(iso: string | null): string {
  if (!iso) return "";
  const hours = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 3_600_000));
  return hours < 24 ? `${hours}h ago` : `${Math.round(hours / 24)}d ago`;
}

/** The verdict, hold period, conviction and status chips — the one-line read of an analysis. */
export function DeepSummaryLine({ a }: { a: DeepAnalysisView }) {
  const v = a.verdict ? VERDICT_STYLE[a.verdict] : null;
  return (
    <>
      {v && (
        <span className={`rounded px-2 py-0.5 text-[11px] font-semibold ${v.cls}`}>
          {v.icon} {v.label}
        </span>
      )}
      {a.verdict === "WORTH_BUYING" && a.holdDays != null && <span className="text-xs font-medium text-accent">⏱ {holdText(a.holdDays)}</span>}
      {a.conviction != null && <span className="text-xs tabular-nums text-text-secondary">conviction {a.conviction}/100</span>}
      {a.thesisStatus === "AT_RISK" && (
        <span title={a.thesisReason ?? undefined} className="rounded bg-losses/15 px-1.5 py-0.5 text-[10px] font-semibold text-losses">
          ⚠ thesis at risk
        </span>
      )}
    </>
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

/** Everything Agent 11 concluded and why — used on the Intelligence page and inside an on-demand research job. */
export function DeepAnalysisDetail({ a, onRerun }: { a: DeepAnalysisView; onRerun?: () => void }) {
  return (
    <div className="flex flex-col gap-3">
      {a.thesisStatus === "AT_RISK" && (
        <div className="rounded-lg bg-losses/10 px-3 py-2">
          <p className="text-[10px] font-medium uppercase tracking-wide text-losses">Thesis at risk — re-analysis queued</p>
          <p className="text-xs text-text-primary">{a.thesisReason}</p>
        </div>
      )}
      {a.thesis && <Block title="Thesis">{a.thesis}</Block>}
      <div className="grid gap-3 sm:grid-cols-2">
        {a.bullCase && <Block title="Best case for owning it">{a.bullCase}</Block>}
        {a.bearCase && <Block title="Best case against">{a.bearCase}</Block>}
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {a.risks.length > 0 && (
          <Block title="Risks">
            <List items={a.risks} />
          </Block>
        )}
        {a.catalysts.length > 0 && (
          <Block title="Catalysts to watch">
            <List items={a.catalysts} />
          </Block>
        )}
      </div>
      {a.invalidation && (
        <Block title="What would change its mind">
          {a.invalidation}
          {a.invalidationPrice != null && (
            <span className="text-text-secondary">
              {" "}
              · price line: <Sensitive>{money(a.invalidationPrice)}</Sensitive>
              {a.priceAtAnalysis != null && (
                <>
                  {" "}
                  (price when analysed <Sensitive>{money(a.priceAtAnalysis)}</Sensitive>)
                </>
              )}
            </span>
          )}
        </Block>
      )}
      {a.guardNotes.length > 0 && (
        <div className="rounded-lg bg-warning/10 px-3 py-2">
          <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-warning">Guardrails applied to the model&apos;s verdict</p>
          <List items={a.guardNotes} />
        </div>
      )}
      <details className="rounded-lg border border-border px-3 py-2">
        <summary className="cursor-pointer text-[11px] text-text-secondary">The specialists&apos; reasoning</summary>
        <div className="mt-2 flex flex-col gap-3">
          {a.technicalSummary && (
            <Block title={`Chart technician${a.technicalScore != null ? ` · deterministic score ${a.technicalScore}` : ""}`}>{a.technicalSummary}</Block>
          )}
          {a.fundamentalSummary && (
            <Block title={`Fundamental analyst${a.fundamentalScore != null ? ` · deterministic score ${a.fundamentalScore}` : ""}`}>
              {a.fundamentalSummary}
            </Block>
          )}
          {a.catalystSummary && <Block title="News & catalyst analyst">{a.catalystSummary}</Block>}
          {a.macroSummary && <Block title="Macro & sector strategist">{a.macroSummary}</Block>}
          {a.skepticView && <Block title="Skeptic">{a.skepticView}</Block>}
        </div>
      </details>
      <div className="flex flex-wrap items-center justify-between gap-2 text-[10px] text-text-secondary">
        <span>
          {a.model} · consensus {a.consensusScore ?? "–"} · {ageText(a.finishedAt)}
        </span>
        {onRerun && (
          <button type="button" onClick={onRerun} className="rounded border border-accent/40 px-2 py-1 font-medium text-accent hover:bg-accent/10">
            Re-analyze now
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Agent 11's structured verdict for one ticker, with live progress and a "Run deep analysis" button — the view an
 * on-demand research job (Agent 9) shows next to its own report, so the deep read is one click away from the research.
 * Polls only while a run is in progress.
 */
export function DeepAnalysisForTicker({ ticker }: { ticker: string }) {
  const [view, setView] = useState<DeepTickerView | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getDeepAnalysisFor(ticker)
      .then((v) => setView(v))
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setView(null);
        else setView((cur) => cur ?? null);
      });
  }, [ticker]);

  useEffect(() => {
    load();
  }, [load]);

  const running = view?.inProgress != null;
  useEffect(() => {
    if (!running) return;
    const id = setInterval(load, 5_000);
    return () => clearInterval(id);
  }, [running, load]);

  async function run() {
    setError(null);
    try {
      await runDeepAnalysis(ticker);
      load();
    } catch {
      setError("Couldn't queue the analysis.");
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Deep analysis · Agent 11 · <Sensitive>{ticker}</Sensitive>
        </h3>
        {!running && (
          <button type="button" onClick={() => void run()} className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent hover:bg-accent/10">
            {view?.latest ? "Re-run deep analysis" : "Run deep analysis"}
          </button>
        )}
      </div>
      {error && <p className="text-xs text-losses">{error}</p>}
      {view?.inProgress && (
        <p className="rounded-lg bg-accent/10 px-3 py-2 text-xs text-accent">
          ⏳ {view.inProgress.status === "QUEUED" ? "Queued" : "Working"}: {view.inProgress.stage ?? "starting"} — a full analysis can take several minutes to hours.
        </p>
      )}
      {view === undefined ? (
        <Skeleton className="h-16 w-full" />
      ) : view?.latest ? (
        <>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <DeepSummaryLine a={view.latest} />
            <span className="min-w-0 flex-1 truncate text-xs text-text-secondary">{view.latest.headline}</span>
          </div>
          <DeepAnalysisDetail a={view.latest} />
        </>
      ) : (
        !running && <p className="text-xs text-text-secondary">Agent 11 has not analysed this ticker yet. Run it to get a verdict — worth buying, wait, or not worth buying — and how long to hold.</p>
      )}
    </section>
  );
}
