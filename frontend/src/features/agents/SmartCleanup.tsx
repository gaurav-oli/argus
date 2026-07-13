"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import {
  getLastCleanup,
  previewCleanup,
  runCleanup,
  type CleanupLastRun,
  type CleanupReport,
} from "@/lib/apiClient";
import { absTime } from "@/lib/time";

/**
 * Smart Cleanup agent (Ops). Keeps raw firehose data by its future linking value — event-anchored,
 * actively-tracked, recent, or strong-signal rows survive; the rest is rolled up into daily summaries
 * and removed. Preview first (deletes nothing), review the plan, then run it for real. On demand only.
 */
export function SmartCleanup() {
  const [last, setLast] = useState<CleanupLastRun | null | undefined>(undefined);
  const [report, setReport] = useState<CleanupReport | null>(null);
  const [busy, setBusy] = useState<"preview" | "run" | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getLastCleanup()
      .then((v) => active && setLast(v))
      .catch(() => active && setLast(null));
    return () => {
      active = false;
    };
  }, []);

  async function onPreview() {
    setBusy("preview");
    setError(null);
    setConfirming(false);
    try {
      setReport(await previewCleanup());
    } catch {
      setError("Couldn't compute the plan just now.");
    } finally {
      setBusy(null);
    }
  }

  async function onRun() {
    setBusy("run");
    setError(null);
    try {
      const r = await runCleanup();
      setReport(r);
      setConfirming(false);
      setLast({
        startedAt: r.startedAt,
        dryRun: r.dryRun,
        deletedRows: r.deletedRows,
        keptRows: r.keptRows,
        rolledUpDays: r.rolledUpDays,
        freedBytes: r.freedBytes,
        summary: r.summary,
      });
    } catch {
      setError("Cleanup failed to run.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-xl">
          <h3 className="font-display text-base font-semibold text-text-primary">Smart cleanup</h3>
          <p className="mt-0.5 text-xs leading-relaxed text-text-secondary">
            Keeps data by its future value: rows tied to a real event, your tracked tickers, recent
            activity, or a strong signal survive. The rest is rolled up into daily summaries (so the
            pattern lives on) and then removed. Preview changes nothing.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={onPreview}
            disabled={busy !== null}
            className="rounded-lg border border-[var(--hairline)] px-3 py-1.5 text-xs font-semibold text-text-primary transition hover:bg-border/20 disabled:opacity-60"
          >
            {busy === "preview" ? "Analyzing…" : "Preview cleanup"}
          </button>
          {report?.dryRun && report.deletedRows > 0 && !confirming && (
            <button
              type="button"
              onClick={() => setConfirming(true)}
              disabled={busy !== null}
              className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white transition hover:opacity-90 disabled:opacity-60"
            >
              Clean up now
            </button>
          )}
        </div>
      </div>

      {last !== undefined && !report && (
        <p className="text-[11px] text-text-secondary">
          {last === null ? (
            "Never run yet — Preview to see what it would do."
          ) : (
            <>
              Last {last.dryRun ? "preview" : "run"} {absTime(last.startedAt)} — {last.summary}
            </>
          )}
        </p>
      )}

      {error && <p className="text-xs italic text-losses">{error}</p>}

      {confirming && (
        <div className="rounded-lg border border-accent/30 bg-accent/[0.06] p-3 text-xs">
          <p className="text-text-primary">
            This permanently deletes {report?.deletedRows.toLocaleString()} raw rows (after rolling them
            up). The learning corpus, open trades, and your portfolio are never touched. Continue?
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={onRun}
              disabled={busy !== null}
              className="rounded-md bg-accent px-3 py-1 font-semibold text-white transition hover:opacity-90 disabled:opacity-60"
            >
              {busy === "run" ? "Cleaning…" : "Yes, clean up"}
            </button>
            <button
              type="button"
              onClick={() => setConfirming(false)}
              className="rounded-md px-3 py-1 font-medium text-text-secondary hover:text-text-primary"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {report && <ReportTable report={report} />}
    </MotionCard>
  );
}

function ReportTable({ report }: { report: CleanupReport }) {
  return (
    <div>
      <div
        className={`mb-2 rounded-md px-3 py-2 text-xs font-medium ${
          report.dryRun ? "bg-border/20 text-text-primary" : "bg-gains/10 text-gains"
        }`}
      >
        {report.dryRun ? "Preview — nothing deleted. " : "Done. "}
        {report.summary}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="text-[10px] uppercase tracking-wider text-text-secondary">
              <th className="pb-1 pr-2 font-medium">Source</th>
              <th className="pb-1 px-2 text-right font-medium">Total</th>
              <th className="pb-1 px-2 text-right font-medium">{report.dryRun ? "Would delete" : "Deleted"}</th>
              <th className="pb-1 px-2 text-right font-medium">Kept: recent</th>
              <th className="pb-1 px-2 text-right font-medium">Kept: event-linked</th>
              <th className="pb-1 px-2 text-right font-medium">Rolled-up days</th>
              <th className="pb-1 pl-2 text-right font-medium">Frees</th>
            </tr>
          </thead>
          <tbody className="text-text-secondary">
            {report.sources.map((s) => (
              <tr key={s.table} className="border-t border-[var(--hairline)]/60">
                <td className="py-1 pr-2">
                  <span className="text-text-primary">{s.kind}</span>
                  <span className="block font-mono text-[10px] text-text-secondary/80">{s.table}</span>
                </td>
                <td className="py-1 px-2 text-right font-mono tabular-nums">{s.rowsTotal.toLocaleString()}</td>
                <td className="py-1 px-2 text-right font-mono tabular-nums text-text-primary">
                  {s.affected.toLocaleString()}
                </td>
                <td className="py-1 px-2 text-right font-mono tabular-nums">{s.keptRecent.toLocaleString()}</td>
                <td className="py-1 px-2 text-right font-mono tabular-nums">{s.keptAnchored.toLocaleString()}</td>
                <td className="py-1 px-2 text-right font-mono tabular-nums">{s.rollupDays.toLocaleString()}</td>
                <td className="py-1 pl-2 text-right font-mono tabular-nums">{formatBytes(s.freedBytes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}
