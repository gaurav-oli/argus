"use client";

import { CompanyIcon } from "@/components/ui/CompanyIcon";
import {
  getAuthStatus,
  getOpenTradeReview,
  startOpenTradeReview,
  type OpenTradeReviewJob,
  type OpenTradeReviewTicker,
} from "@/lib/apiClient";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { cn } from "@/lib/utils";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useMemo, useState } from "react";

const POLL_MS = 1_000;

const STEPS: { key: OpenTradeReviewJob["step"]; label: string }[] = [
  { key: "LOADING", label: "Load the open book" },
  { key: "REVIEWING", label: "Agent 5 re-reviews each stock" },
  { key: "SUMMARIZING", label: "Summarize" },
  { key: "DONE", label: "Done" },
];
const ORDER: Record<string, number> = { LOADING: 0, REVIEWING: 1, SUMMARIZING: 2, DONE: 3 };

/** Call badge colours: buys green, avoids red, watch neutral. */
function callClass(call: string | null): string {
  if (!call) return "border-[var(--glass-border)] text-text-tertiary";
  const c = call.toLowerCase();
  if (c.includes("avoid")) return "border-losses/40 bg-losses/10 text-losses";
  if (c.includes("buy")) return "border-gains/40 bg-gains/10 text-gains";
  return "border-[var(--glass-border)] text-text-secondary";
}

/** Exits first, then tightened stops, new trades, holding. */
function rank(r: OpenTradeReviewTicker): number {
  if (r.exited.length > 0) return 0;
  if (r.stopsTightened > 0) return 1;
  if (r.newLegs > 0) return 2;
  return 3;
}

/**
 * Admin-only "Re-review open trades now", shown as a live pipeline: the review runs in the background and this
 * polls its status every second — which step it is on, which stock it is reviewing and what is happening to it,
 * a progress bar — then lays the results out as a table: each stock's new call and conviction, and what that did
 * to its open trades (exited and why, stop tightened, new trade, or holding), exits first.
 */
export function OpenTradeReviewPanel({ onFinished }: { onFinished: () => void }) {
  const [isAdmin, setIsAdmin] = useState(false);
  const [job, setJob] = useState<OpenTradeReviewJob | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reduce = useReducedMotion();

  useEffect(() => {
    getAuthStatus()
      .then((s) => {
        const admin = Boolean(s.user?.admin);
        setIsAdmin(admin);
        if (admin) getOpenTradeReview().then((j) => setJob(j ?? null)).catch(() => {});
      })
      .catch(() => {});
  }, []);

  const running = job != null && job.finishedAt == null;
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => {
      getOpenTradeReview()
        .then((j) => {
          setJob(j ?? null);
          // Finished: refresh the scoreboard so exits and tightened stops show in the book above. Polling stops
          // with this update (running turns false), so it happens once per review.
          if (j?.finishedAt) onFinished();
        })
        .catch(() => {});
    }, POLL_MS);
    return () => clearInterval(t);
  }, [running, onFinished]);

  const logos = useCompanyLogos(useMemo(() => (job?.results ?? []).map((r) => r.ticker), [job]));

  if (!isAdmin) return null;

  async function start() {
    setError(null);
    try {
      setJob(await startOpenTradeReview());
    } catch {
      setError("Couldn't start the review — try again in a moment.");
    }
  }

  const pct = job && job.total > 0 ? Math.round((job.done / job.total) * 100) : job?.step === "DONE" ? 100 : 0;
  const rows = [...(job?.results ?? [])].sort((a, b) => rank(a) - rank(b) || a.ticker.localeCompare(b.ticker));
  const exited = job?.exited ?? job?.results.reduce((n, r) => n + r.exited.length, 0) ?? 0;
  const tightened = job?.stopsTightened ?? job?.results.reduce((n, r) => n + r.stopsTightened, 0) ?? 0;
  const newLegs = job?.results.reduce((n, r) => n + r.newLegs, 0) ?? 0;

  return (
    <div className="flex flex-col gap-3 border-t border-[var(--hairline)] pt-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">Re-review open trades</p>
          <p className="text-[11px] text-text-secondary">
            Agent 5 re-checks every open trade on news, price and events automatically — or run it for the whole book now.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void start()}
          disabled={running}
          className="shrink-0 border border-accent/50 px-3 py-1.5 font-mono text-[11px] font-semibold text-accent transition-colors hover:bg-accent hover:text-background disabled:opacity-50"
        >
          {running ? "Reviewing…" : job ? "Run again" : "Re-review open trades now"}
        </button>
      </div>

      {error && <p className="font-mono text-[11px] text-losses">{error}</p>}

      {job && (
        <div className="flex flex-col gap-3 border border-[var(--glass-border)] bg-[var(--hover-wash)] p-3">
          {job.step === "FROZEN" ? (
            <p className="font-mono text-[11px] text-warning">Agent 5 is frozen — resume it first; a frozen Agent 5 makes no calls.</p>
          ) : job.step === "FAILED" ? (
            <p className="font-mono text-[11px] text-losses">The review stopped: {job.error ?? "unknown error"}</p>
          ) : (
            <>
              {/* Pipeline */}
              <ol className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {STEPS.map((s) => {
                  const at = ORDER[job.step] ?? 0;
                  const mine = ORDER[s.key];
                  const state = mine < at || job.step === "DONE" ? "done" : mine === at ? "active" : "pending";
                  return (
                    <li
                      key={s.key}
                      className={cn(
                        "flex items-center gap-2 border px-2 py-1.5 font-mono text-[10.5px]",
                        state === "done" && "border-gains/40 text-gains",
                        state === "active" && "border-accent text-accent",
                        state === "pending" && "border-[var(--glass-border)] text-text-tertiary",
                      )}
                    >
                      <span aria-hidden className="w-3 text-center">
                        {state === "done" ? "✓" : state === "active" ? (
                          <span className="inline-block h-2.5 w-2.5 animate-spin rounded-full border-2 border-accent/30 border-t-accent" />
                        ) : (
                          "○"
                        )}
                      </span>
                      {s.label}
                      {s.key === "REVIEWING" && job.total > 0 && ` · ${job.done}/${job.total}`}
                    </li>
                  );
                })}
              </ol>

              {/* Progress */}
              <div>
                <div className="h-1.5 w-full overflow-hidden bg-[var(--hairline)]">
                  <motion.div
                    className="h-full bg-accent"
                    initial={false}
                    animate={{ width: `${pct}%` }}
                    transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 120, damping: 20 }}
                  />
                </div>
                {running && (
                  <p className="mt-1.5 font-mono text-[11px] text-text-secondary">
                    {job.currentTicker ? (
                      <>
                        <span className="text-accent">▸ {job.currentTicker}</span> — {job.currentStage}
                      </>
                    ) : (
                      job.currentStage ?? "Starting…"
                    )}
                  </p>
                )}
              </div>

              {/* Totals */}
              {job.results.length > 0 && (
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Total label="Stocks reviewed" value={`${job.done}${job.total ? `/${job.total}` : ""}`} />
                  <Total label="Trades exited" value={String(exited)} tone={exited > 0 ? "warning" : undefined} />
                  <Total label="Stops tightened" value={String(tightened)} />
                  <Total label="New trades" value={String(newLegs)} tone={newLegs > 0 ? "gains" : undefined} />
                </div>
              )}

              {/* Results */}
              {rows.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[36rem] text-left font-mono text-[11px] tabular-nums">
                    <thead className="border-b border-[var(--hairline)] text-[10px] uppercase tracking-wider text-text-secondary">
                      <tr>
                        <th className="py-1.5 font-normal">Stock</th>
                        <th className="py-1.5 font-normal">New call</th>
                        <th className="py-1.5 text-right font-normal">Conviction</th>
                        <th className="py-1.5 text-right font-normal">Open trades</th>
                        <th className="py-1.5 pl-4 font-normal">What happened</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((r) => (
                        <motion.tr
                          key={r.ticker}
                          initial={reduce ? false : { opacity: 0, y: 4 }}
                          animate={{ opacity: 1, y: 0 }}
                          className="border-b border-[var(--hairline)] last:border-b-0"
                        >
                          <td className="py-1.5">
                            <span className="flex items-center gap-2">
                              <CompanyIcon ticker={r.ticker} logoUrl={logos[r.ticker]} title={r.ticker} size={18} />
                              <span className="font-semibold text-text-primary">{r.ticker}</span>
                            </span>
                          </td>
                          <td className="py-1.5">
                            <span className={cn("border px-1.5 py-0.5 text-[10px]", callClass(r.call))}>
                              {r.call ?? "no call"}
                            </span>
                          </td>
                          <td className="py-1.5 text-right text-text-primary">{r.conviction ?? "—"}</td>
                          <td className="py-1.5 text-right text-text-secondary">{r.openBefore}</td>
                          <td className="py-1.5 pl-4">
                            <span className="flex flex-wrap gap-1">
                              {r.exited.map((e, i) => (
                                <span key={i} className="border border-warning/40 bg-warning/10 px-1.5 py-0.5 text-[10px] text-warning">
                                  exited · {e}
                                </span>
                              ))}
                              {r.stopsTightened > 0 && (
                                <span className="border border-accent/40 bg-accent/10 px-1.5 py-0.5 text-[10px] text-accent">
                                  {r.stopsTightened} stop{r.stopsTightened === 1 ? "" : "s"} tightened
                                </span>
                              )}
                              {r.newLegs > 0 && (
                                <span className="border border-gains/40 bg-gains/10 px-1.5 py-0.5 text-[10px] text-gains">
                                  {r.newLegs} new trade{r.newLegs === 1 ? "" : "s"}
                                </span>
                              )}
                              {rank(r) === 3 && <span className="text-[10px] text-text-tertiary">holding — the call still agrees</span>}
                            </span>
                          </td>
                        </motion.tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {job.finishedAt && (
                <p className="font-mono text-[10px] text-text-tertiary">
                  Finished {new Date(job.finishedAt).toLocaleTimeString()} ·{" "}
                  {Math.max(1, Math.round((new Date(job.finishedAt).getTime() - new Date(job.startedAt).getTime()) / 1000))}s
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function Total({ label, value, tone }: { label: string; value: string; tone?: "warning" | "gains" }) {
  return (
    <div className="border border-[var(--glass-border)] px-2.5 py-1.5">
      <p className="text-[9.5px] uppercase tracking-wider text-text-secondary">{label}</p>
      <p
        className={cn(
          "font-display text-lg font-bold tabular-nums",
          tone === "warning" ? "text-warning" : tone === "gains" ? "text-gains" : "text-text-primary",
        )}
      >
        {value}
      </p>
    </div>
  );
}
