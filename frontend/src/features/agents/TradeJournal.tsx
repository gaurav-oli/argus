"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getJournal,
  getJournalEntry,
  type JournalDetailView,
  type JournalEntryView,
} from "@/lib/apiClient";
import { cn } from "@/lib/utils";

/**
 * Trade Journal (Story 11.1, F22): every Taken/Declined recommendation decision, most recent first,
 * with its frozen FR-15 rationale snapshot and — once a matching paper leg closes — how Agent 5's call
 * actually played out. Read-only; sits next to the aggregate Regret analysis card it complements with
 * per-decision detail.
 */
export function TradeJournal() {
  const [entries, setEntries] = useState<JournalEntryView[] | null>(null);

  useEffect(() => {
    let active = true;
    getJournal()
      .then((e) => active && setEntries(e))
      .catch(() => active && setEntries([]));
    return () => {
      active = false;
    };
  }, []);

  return (
    <MotionCard index={4} interactive={false} className="flex flex-col gap-4">
      <SectionHead
        title="Trade Journal"
        sub="Every recommendation you've taken or declined, with the reasoning frozen at the moment you decided."
      />
      {entries === null ? (
        <Skeleton className="h-40" />
      ) : entries.length === 0 ? (
        <Empty>Decide on a recommendation (Take or Decline) to start your journal.</Empty>
      ) : (
        <ul className="flex flex-col divide-y divide-border/60">
          {entries.map((e) => (
            <JournalRow key={e.decisionId} entry={e} />
          ))}
        </ul>
      )}
    </MotionCard>
  );
}

function JournalRow({ entry }: { entry: JournalEntryView }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<JournalDetailView | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  function toggle() {
    setOpen((o) => !o);
    if (!open && detail === null && !loadingDetail) {
      setLoadingDetail(true);
      getJournalEntry(entry.decisionId)
        .then(setDetail)
        .catch(() => {})
        .finally(() => setLoadingDetail(false));
    }
  }

  return (
    <li className="py-3">
      <button onClick={toggle} className="flex w-full items-center justify-between gap-3 text-left">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-text-primary">{entry.ticker}</span>
          <span
            className={cn(
              "text-xs font-medium",
              entry.direction === "BULLISH" ? "text-gains" : "text-losses",
            )}
          >
            {entry.direction === "BULLISH" ? "▲" : "▼"}
          </span>
          <span
            className={cn(
              "rounded px-1.5 py-0.5 text-[10px] font-medium",
              entry.decision === "TAKEN" ? "bg-gains/15 text-gains" : "bg-border/60 text-text-secondary",
            )}
          >
            {entry.decision === "TAKEN" ? "Taken" : "Declined"}
          </span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[11px] text-text-secondary">
            {new Date(entry.decidedAt).toLocaleDateString()}
          </span>
          <OutcomeBadge outcome={entry.outcome} returnPct={entry.outcomeReturnPct} />
        </div>
      </button>

      {open && (
        <div className="mt-3 border-t border-border/60 pt-3">
          {loadingDetail || detail === null ? (
            <Skeleton className="h-24" />
          ) : (
            <JournalDetail detail={detail} />
          )}
        </div>
      )}
    </li>
  );
}

function JournalDetail({ detail }: { detail: JournalDetailView }) {
  const bull = detail.bullProbability != null ? Math.round(detail.bullProbability * 100) : null;
  return (
    <div className="flex flex-col gap-3 text-xs">
      {(bull != null || detail.confidence != null) && (
        <p className="text-text-secondary">
          At decision time: {bull != null && <>{bull}% bullish</>}
          {detail.confidence != null && <> · {Math.round(detail.confidence * 100)}% confidence</>}
        </p>
      )}

      {detail.reasoning && (
        <p className="text-text-primary">
          <span className="font-medium text-text-secondary">Your reasoning: </span>
          {detail.reasoning}
        </p>
      )}

      {(detail.entryPrice != null || detail.positionSize != null) && (
        <p className="text-text-secondary">
          {detail.entryPrice != null && <>Entry ${detail.entryPrice.toFixed(2)}</>}
          {detail.entryPrice != null && detail.positionSize != null && " · "}
          {detail.positionSize != null && <>{detail.positionSize} shares</>}
        </p>
      )}

      {detail.signals.length > 0 && (
        <div>
          <p className="mb-1 font-medium text-text-secondary">Signals at decision time</p>
          <ul className="flex flex-col gap-1">
            {detail.signals.map((s, i) => (
              <li key={i} className="text-text-secondary">
                <span className="font-medium text-text-primary">{s.agent}</span> · {s.direction.toLowerCase()}
                {s.weight != null && <> · w{s.weight.toFixed(2)}</>}
                {s.rationale && <> — {s.rationale}</>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {detail.personaVerdicts.length > 0 && (
        <div>
          <p className="mb-1 font-medium text-text-secondary">Persona takes at decision time</p>
          <ul className="flex flex-col gap-1">
            {detail.personaVerdicts.map((p, i) => (
              <li key={i} className="text-text-secondary">
                <span className="font-medium text-text-primary">{p.persona}</span> · {p.stance.toLowerCase()}
                {p.rationale && <> — {p.rationale}</>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <p className="text-[10px] text-text-secondary/80">
        {detail.outcome === "PENDING"
          ? "Outcome pending — no closed paper leg for this recommendation yet."
          : "Outcome is the Investor's paper-trade return vs SPY for this recommendation, the same figure Regret analysis uses."}
      </p>
    </div>
  );
}

function OutcomeBadge({
  outcome,
  returnPct,
}: {
  outcome: JournalEntryView["outcome"];
  returnPct: number | null;
}) {
  if (outcome === "PENDING") {
    return <span className="text-[11px] text-text-secondary">Pending</span>;
  }
  const won = outcome === "WIN";
  return (
    <span
      className={cn(
        "rounded px-1.5 py-0.5 text-[10px] font-semibold tabular-nums",
        won ? "bg-gains/15 text-gains" : "bg-losses/15 text-losses",
      )}
    >
      {won ? "Win" : "Loss"}
      {returnPct != null && <> {returnPct > 0 ? "+" : ""}{returnPct.toFixed(1)}%</>}
    </span>
  );
}

function SectionHead({ title, sub }: { title: string; sub?: string }) {
  return (
    <div>
      <h3 className="font-display text-base font-semibold text-text-primary">{title}</h3>
      {sub && <p className="mt-0.5 text-xs text-text-secondary">{sub}</p>}
    </div>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className={cn("py-6 text-center text-xs text-text-secondary")}>{children}</p>;
}
