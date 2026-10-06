"use client";

import { useMemo, useState } from "react";

import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { Sensitive } from "@/features/privacy/Sensitive";
import {
  getJournal,
  getJournalEntry,
  type JournalDetailView,
  type JournalEntryView,
} from "@/lib/apiClient";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { cn } from "@/lib/utils";
import { FilterChips, Pager, SortHeader, compareBy, usePaged, useSort } from "./tableKit";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * Trade Journal (Story 11.1, F22): the 100 most recent Taken/Declined recommendation decisions,
 * with its frozen FR-15 rationale snapshot and — once a matching paper leg closes — how Agent 5's call
 * actually played out. Read-only; sits next to the aggregate Regret analysis card it complements with
 * per-decision detail. Shown as a filterable, sortable, paged table so the full history stays
 * reachable without one long scroll.
 */
export function TradeJournal() {
  const [entries, setEntries] = useState<JournalEntryView[] | null>(null);

  useAutoRefresh(() =>
    getJournal()
      .then(setEntries)
      .catch(() => setEntries((prev) => prev ?? [])),
  );

  const logos = useCompanyLogos(useMemo(() => (entries ?? []).map((e) => e.ticker), [entries]));

  return (
    <MotionCard index={4} interactive={false} className="flex flex-col gap-4">
      <SectionHead
        title="Trade Journal"
        sub="Every recommendation taken or declined — by the Investor persona's own paper trades, or by you — with the reasoning frozen at that moment."
      />
      {entries === null ? (
        <Skeleton className="h-40" />
      ) : entries.length === 0 ? (
        <Empty>Nothing decided yet — entries appear as soon as the Investor persona acts on a recommendation.</Empty>
      ) : (
        <JournalTable entries={entries} logos={logos} />
      )}
    </MotionCard>
  );
}

type DecisionFilter = "all" | "TAKEN" | "DECLINED";
type WhoFilter = "all" | "AGENT" | "USER";
type OutcomeFilter = "all" | "WIN" | "LOSS" | "PENDING";
type JournalKey = "ticker" | "date" | "outcome";

/** Rows per page in the journal. */
const PAGE = 12;

/**
 * The journal as a filterable, sortable, paged table: decision / who decided / outcome filters, a
 * ticker search, and the same lazily-loaded detail (odds, reasoning, entry, signals, persona takes)
 * opening under any row. Nothing from the old list is dropped — it is just a page at a time.
 */
function JournalTable({ entries, logos }: { entries: JournalEntryView[]; logos: Record<string, string> }) {
  const [decision, setDecision] = useState<DecisionFilter>("all");
  const [who, setWho] = useState<WhoFilter>("all");
  const [outcome, setOutcome] = useState<OutcomeFilter>("all");
  const [query, setQuery] = useState("");
  const [sort, onSort] = useSort<JournalKey>({ key: "date", dir: "desc" });

  const count = (pred: (e: JournalEntryView) => boolean) => entries.filter(pred).length;
  const rows = useMemo(() => {
    const q = query.trim().toUpperCase();
    const get = {
      ticker: (e: JournalEntryView) => e.ticker,
      date: (e: JournalEntryView) => e.decidedAt,
      outcome: (e: JournalEntryView) => e.outcomeReturnPct,
    }[sort.key];
    return entries
      .filter((e) => decision === "all" || e.decision === decision)
      .filter((e) => who === "all" || e.source === who)
      .filter((e) => outcome === "all" || e.outcome === outcome)
      .filter((e) => q === "" || e.ticker.toUpperCase().includes(q))
      .sort(compareBy(get, sort.dir));
  }, [entries, decision, who, outcome, query, sort]);
  const paged = usePaged(rows, PAGE);

  const wins = count((e) => e.outcome === "WIN");
  const losses = count((e) => e.outcome === "LOSS");

  return (
    <div className="flex flex-col gap-3">
      <p className="font-mono text-xs text-text-secondary">
        {entries.length} decisions · {count((e) => e.decision === "TAKEN")} taken · {count((e) => e.decision === "DECLINED")} declined ·{" "}
        <span className="text-gains">{wins} won</span> · <span className="text-losses">{losses} lost</span> · {count((e) => e.outcome === "PENDING")} pending
      </p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <FilterChips
          label="Decision"
          value={decision}
          onChange={setDecision}
          options={[
            { value: "all", label: "All" },
            { value: "TAKEN", label: "Taken", count: count((e) => e.decision === "TAKEN") },
            { value: "DECLINED", label: "Declined", count: count((e) => e.decision === "DECLINED") },
          ]}
        />
        <FilterChips
          label="Decided by"
          value={who}
          onChange={setWho}
          options={[
            { value: "all", label: "Anyone" },
            { value: "AGENT", label: "Investor", count: count((e) => e.source === "AGENT") },
            { value: "USER", label: "You", count: count((e) => e.source === "USER") },
          ]}
        />
        <FilterChips
          label="Outcome"
          value={outcome}
          onChange={setOutcome}
          options={[
            { value: "all", label: "Any result" },
            { value: "WIN", label: "Win", count: wins },
            { value: "LOSS", label: "Loss", count: losses },
            { value: "PENDING", label: "Pending", count: count((e) => e.outcome === "PENDING") },
          ]}
        />
        <label className="ml-auto flex items-center gap-1.5 font-mono text-[11px] text-text-secondary">
          <span aria-hidden className="text-accent">&gt;</span>
          <span className="sr-only">Search by ticker</span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="ticker"
            maxLength={12}
            className="w-24 border border-[var(--glass-border)] bg-transparent px-2 py-1 text-text-primary outline-none focus:border-accent"
          />
        </label>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[34rem] font-mono text-xs tabular-nums">
          <thead className="border-b border-[var(--hairline)] text-left text-[10px]">
            <tr>
              <SortHeader label="Ticker" k="ticker" sort={sort} onSort={onSort} />
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Call</th>
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Decision</th>
              <th scope="col" className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">By</th>
              <SortHeader label="Date" k="date" sort={sort} onSort={onSort} />
              <SortHeader label="Outcome" k="outcome" sort={sort} onSort={onSort} className="text-right" />
            </tr>
          </thead>
          <tbody>
            {paged.rows.map((e) => (
              <JournalRow key={e.decisionId} entry={e} logoUrl={logos[e.ticker]} />
            ))}
            {paged.rows.length === 0 && (
              <tr>
                <td colSpan={6} className="py-4 text-center text-text-secondary">
                  No decisions match these filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <Pager p={paged} />
    </div>
  );
}

function JournalRow({ entry, logoUrl }: { entry: JournalEntryView; logoUrl: string | undefined }) {
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
    <>
      <tr className={cn("cursor-pointer border-b border-[var(--hairline)]/60 hover:bg-[var(--hover-wash)]", open && "bg-[var(--hover-wash)]")} onClick={toggle}>
        <td className="py-1.5">
          <span className="flex items-center gap-2">
            <CompanyIcon ticker={entry.ticker} logoUrl={logoUrl} title={entry.ticker} size={16} />
            <button type="button" aria-expanded={open} className="font-semibold text-text-primary hover:text-accent">
              {entry.ticker}
              <span aria-hidden className="ml-1 text-text-secondary">{open ? "▾" : "▸"}</span>
            </button>
          </span>
        </td>
        <td className={cn("py-1.5", entry.direction === "BULLISH" ? "text-gains" : "text-losses")}>
          {entry.direction === "BULLISH" ? "▲ bull" : "▼ bear"}
        </td>
        <td className="py-1.5">
          <span
            className={cn(
              "px-1.5 py-0.5 text-[10px] font-medium",
              entry.decision === "TAKEN" ? "bg-gains/15 text-gains" : "bg-border/60 text-text-secondary",
            )}
          >
            {entry.decision === "TAKEN" ? "Taken" : "Declined"}
          </span>
        </td>
        <td className="py-1.5">
          <SourceBadge source={entry.source} />
        </td>
        <td className="py-1.5 text-[11px] text-text-secondary">{new Date(entry.decidedAt).toLocaleDateString()}</td>
        <td className="py-1.5 text-right">
          <OutcomeBadge outcome={entry.outcome} returnPct={entry.outcomeReturnPct} />
        </td>
      </tr>
      {open && (
        <tr className="border-b border-[var(--hairline)]/60 bg-[var(--hover-wash)]">
          <td colSpan={6} className="term-line-in px-2 py-3 font-sans">
            {loadingDetail || detail === null ? <Skeleton className="h-24" /> : <JournalDetail detail={detail} />}
          </td>
        </tr>
      )}
    </>
  );
}

function JournalDetail({ detail }: { detail: JournalDetailView }) {
  const bull = detail.bullProbability != null ? Math.round(detail.bullProbability * 100) : null;
  return (
    <div className="grid grid-cols-1 gap-3 text-xs lg:grid-cols-2">
      {(bull != null || detail.confidence != null) && (
        <p className="text-text-secondary">
          At decision time: {bull != null && <>{bull}% bullish</>}
          {detail.confidence != null && <> · {Math.round(detail.confidence * 100)}% confidence</>}
        </p>
      )}

      {detail.reasoning && (
        <p className="text-text-primary">
          <span className="font-medium text-text-secondary">
            {detail.source === "AGENT" ? "Investor's reasoning: " : "Your reasoning: "}
          </span>
          {detail.reasoning}
        </p>
      )}

      {(detail.entryPrice != null || detail.positionSize != null) && (
        <p className="text-text-secondary">
          <Sensitive>
            <>
              {detail.entryPrice != null && <>Entry ${detail.entryPrice.toFixed(2)}</>}
              {detail.entryPrice != null && detail.positionSize != null && " · "}
              {detail.positionSize != null && <>{detail.positionSize} shares</>}
            </>
          </Sensitive>
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

      <p className="text-[10px] text-text-secondary/80 lg:col-span-2">
        {detail.outcome === "PENDING"
          ? "Outcome pending — no closed paper leg for this recommendation yet."
          : "Outcome is the Investor's paper-trade return vs SPY for this recommendation, the same figure Regret analysis uses."}
      </p>
    </div>
  );
}

function SourceBadge({ source }: { source: JournalEntryView["source"] }) {
  return (
    <span
      className={cn(
        "rounded px-1.5 py-0.5 text-[10px] font-medium",
        source === "AGENT" ? "bg-accent/15 text-accent" : "bg-border/60 text-text-secondary",
      )}
      title={source === "AGENT" ? "The Investor persona's own paper trade" : "Confirmed by you"}
    >
      {source === "AGENT" ? "Agent" : "You"}
    </span>
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
