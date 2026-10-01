"use client";

import { getStrategyLibrary, revalidateStrategies, type StrategyLibrary, type StrategyRow } from "@/lib/apiClient";
import { Skeleton } from "@/components/ui/Skeleton";
import { useCallback, useEffect, useState } from "react";

const STATUS_STYLE: Record<StrategyRow["status"], { label: string; cls: string }> = {
  ACTIVE: { label: "validated", cls: "bg-gains/15 text-gains" },
  CANDIDATE: { label: "awaiting test", cls: "bg-warning/15 text-warning" },
  REJECTED: { label: "failed here", cls: "bg-losses/15 text-losses" },
  UNIMPLEMENTED: { label: "not computable", cls: "bg-[var(--hover-wash)] text-text-secondary" },
};

function signed(n: number | null, digits = 2): string {
  return n == null ? "–" : `${n >= 0 ? "+" : ""}${n.toFixed(digits)}`;
}

/**
 * Agent 15 — the published trading-strategy literature, held to Argus's own evidence.
 *
 * The honest framing matters more than the list: most of these strategies cannot be computed with the data Argus
 * has, a large share of the literature does not replicate at all, and the ones that do often stop working after
 * publication. So the panel leads with how many were thrown out and why, and a strategy only shows as validated
 * once it has beaten a held-back period on Argus's own history — never because its paper reported a big t-stat.
 */
export function StrategyLibraryPanel() {
  const [lib, setLib] = useState<StrategyLibrary | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getStrategyLibrary()
      .then(setLib)
      .catch(() => setLib((cur) => cur ?? null));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function revalidate() {
    setBusy(true);
    setError(null);
    try {
      await revalidateStrategies();
      load();
    } catch {
      setError("Re-validation couldn't finish — it's a heavy pass; check back shortly.");
    } finally {
      setBusy(false);
    }
  }

  const coverage = lib && lib.universeSize > 0 ? Math.round((lib.universeCovered / lib.universeSize) * 100) : 0;

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Academic strategies · Agent 15
          </h2>
          <p className="mt-1 max-w-xl text-xs text-text-secondary">
            Published return-predictor strategies with their original papers, tested on Argus&apos;s own history across a{" "}
            {lib?.universeSize ?? "500"}-name ranking universe. A strategy only earns a say once it beats a held-back
            period here — a paper&apos;s own t-statistic counts for nothing.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void revalidate()}
          disabled={busy}
          className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent hover:bg-accent/10 disabled:opacity-50"
        >
          {busy ? "Testing…" : "Re-validate"}
        </button>
      </div>

      {error && <p className="text-xs text-losses">{error}</p>}

      {lib === null ? (
        <Skeleton className="h-24 w-full" />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Stat label="in the library" value={lib.total} hint={`${lib.predictors} real predictors`} />
            <Stat label="known placebos" value={lib.placebos} hint="published but don't predict" />
            <Stat label="computable here" value={lib.computable} hint="the rest need paid data" />
            <Stat label="passed validation" value={lib.active} hint={`${lib.rejected} failed on our data`} accent />
          </div>

          {coverage < 95 && (
            <p className="rounded-lg bg-warning/10 px-3 py-2 text-[11px] text-warning">
              Price history is still filling: {lib.universeCovered} of {lib.universeSize} ranking names ({coverage}%).
              Validation stays provisional until the cross-section is complete.
            </p>
          )}

          {lib.strategies.length === 0 ? (
            <p className="text-sm text-text-secondary">No strategies computable yet — the library imports on startup.</p>
          ) : (
            <ul className="flex flex-col gap-1.5">
              {lib.strategies.map((s) => {
                const isOpen = open === s.acronym;
                const st = STATUS_STYLE[s.status];
                return (
                  <li key={s.acronym} className="rounded-lg border border-border">
                    <button
                      type="button"
                      onClick={() => setOpen(isOpen ? null : s.acronym)}
                      className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-left"
                    >
                      <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${st.cls}`}>{st.label}</span>
                      <span className="text-sm font-semibold text-text-primary">{s.name}</span>
                      <span className="text-[11px] text-text-secondary">{s.citation}</span>
                      {s.holdoutTStat != null && (
                        <span className="text-[11px] tabular-nums text-text-secondary">
                          our out-of-sample t={signed(s.holdoutTStat)}
                        </span>
                      )}
                      {s.publishedTStat != null && (
                        <span className="text-[10px] tabular-nums text-text-secondary/70">
                          (paper claimed t={s.publishedTStat.toFixed(2)})
                        </span>
                      )}
                    </button>
                    {isOpen && (
                      <div className="flex flex-col gap-2 border-t border-border px-3 py-3 text-xs">
                        {s.definition && (
                          <Block title="The rule, as the paper defines it">
                            <span className="text-text-primary">{s.definition}</span>
                          </Block>
                        )}
                        {s.note && (
                          <Block title="What Argus measured">
                            <span className="text-text-primary">{s.note}</span>
                          </Block>
                        )}
                        <div className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-text-secondary">
                          {s.horizonDays != null && <span>tested at {s.horizonDays}-day holds</span>}
                          {s.observations != null && <span>{s.observations} non-overlapping periods</span>}
                          {s.measuredExcessPct != null && (
                            <span className="tabular-nums">spread {signed(s.measuredExcessPct)}% per period</span>
                          )}
                          {s.replicationGrade && <span>replication grade: {s.replicationGrade.replace("_", " ")}</span>}
                          {s.sign != null && <span>direction: {s.sign > 0 ? "buy the high end" : "buy the low end"}</span>}
                          {s.dataCategory && <span>data: {s.dataCategory.toLowerCase()}</span>}
                        </div>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          <p className="text-[10px] leading-relaxed text-text-secondary">
            Strategy definitions and replication grades come from Chen &amp; Zimmermann&apos;s Open Source Asset Pricing
            project. Two limitations worth remembering: the ranking universe is today&apos;s index membership applied to
            history, so backtests here are flattered by survivorship bias; and long-short spreads ignore the cost of
            shorting and trading.
          </p>
        </>
      )}
    </section>
  );
}

function Stat({ label, value, hint, accent }: { label: string; value: number; hint: string; accent?: boolean }) {
  return (
    <div className={`rounded-lg px-3 py-2 ${accent ? "bg-accent/10" : "bg-[var(--hover-wash)]"}`}>
      <p className={`text-lg font-bold tabular-nums ${accent ? "text-accent" : "text-text-primary"}`}>{value}</p>
      <p className="text-[10px] uppercase tracking-wide text-text-secondary">{label}</p>
      <p className="text-[10px] text-text-secondary">{hint}</p>
    </div>
  );
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-text-secondary">{title}</p>
      <div className="leading-relaxed">{children}</div>
    </div>
  );
}
