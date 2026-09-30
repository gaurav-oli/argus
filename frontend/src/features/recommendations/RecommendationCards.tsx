"use client";

import {
  decideRecommendation,
  getGraduation,
  getPersonas,
  getRecommendations,
  type GraduationSummary,
  type PersonaTake,
  type PriceGuidance,
  type RecommendationCard as Card,
  type SignalView,
} from "@/lib/apiClient";
import { RecommendationChat } from "@/features/conversation/RecommendationChat";
import { RecommendationDebate } from "@/features/conversation/RecommendationDebate";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Carousel } from "@/components/ui/Carousel";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Skeleton } from "@/components/ui/Skeleton";
import { MarketRegimeStrip } from "@/features/recommendations/MarketRegimeStrip";
import { WatchingList } from "@/features/recommendations/WatchingList";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { useEffect, useMemo, useState } from "react";

/**
 * Recommendation cards (Epic 6 — Agent 5) from /api/recommendations. Only calls with a real edge get a
 * card: an action (Buy / Avoid), a 0–100 conviction score, how long to hold it (short / medium / long),
 * the reasoning and risks behind it, and an exit plan — plus the raw odds, agent signal dots, an
 * expandable diagnostic (Story 6.2) and Taken/Declined actions that snapshot the decision (6.7).
 * Names with no clear edge are listed separately, with the reason, instead of masquerading as calls.
 * Rendered in a horizontal M3-style carousel rather than a stacked grid, so N recommendations don't
 * push the rest of the Intelligence page down the more Agent 5 issues.
 */
export function RecommendationCards() {
  const [cards, setCards] = useState<Card[] | null>(null);
  const [grad, setGrad] = useState<GraduationSummary | null>(null);

  useEffect(() => {
    let active = true;
    getRecommendations()
      .then((c) => active && setCards(c))
      .catch(() => active && setCards([]));
    getGraduation()
      .then((g) => active && setGrad(g))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const logos = useCompanyLogos(useMemo(() => (cards ?? []).map((c) => c.ticker), [cards]));

  if (cards === null) {
    return (
      <section className="rounded-xl border border-border bg-surface p-6">
        <Skeleton className="h-5 w-48" />
      </section>
    );
  }
  if (cards.length === 0) {
    return (
      <section className="flex flex-col gap-4">
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Recommendations · Agent 5
        </h2>
        <MarketRegimeStrip />
        <div className="rounded-xl border border-border bg-surface p-5 text-sm text-text-secondary">
          <p className="font-medium text-text-primary">No high-conviction calls right now.</p>
          <p className="mt-1 text-xs">
            Argus only recommends a trade when several independent sources agree strongly enough to act on. Right now
            none do — that&apos;s the system being selective, not idle. Reasons for each name are below.
          </p>
        </div>
        <WatchingList />
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Recommendations · Agent 5
        </h2>
        {grad && grad.badge && (
          <p className="text-[11px] text-text-secondary">
            <span className="font-medium text-text-primary">{prettyState(grad.state)}</span> — building a track
            record: {grad.trades} confirmed trade{grad.trades === 1 ? "" : "s"}
            {grad.trades > 0 && <> · {grad.winRatePct}% win rate</>} · validates after a proven win rate
          </p>
        )}
      </div>
      <MarketRegimeStrip />
      <Carousel
        items={cards}
        keyOf={(c) => c.id}
        renderItem={(c) => (
          <ForecastCard
            card={c}
            logoUrl={logos[c.ticker]}
            onDecided={(id) => setCards((cs) => cs?.filter((x) => x.id !== id) ?? null)}
          />
        )}
      />
      <WatchingList />
    </section>
  );
}

function ForecastCard({
  card,
  logoUrl,
  onDecided,
}: {
  card: Card;
  logoUrl: string | undefined;
  onDecided: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  // Taking a trade opens a small inline entry-price/shares form (Story 11.1, F22) instead of
  // deciding immediately — both optional, so a blank Confirm behaves exactly like before.
  const [takingOpen, setTakingOpen] = useState(false);
  const [entryPrice, setEntryPrice] = useState("");
  const [positionSize, setPositionSize] = useState("");
  const bull = Math.round(card.bullProbability * 100);
  const decided = card.status === "TAKEN" || card.status === "DECLINED";

  async function decide(decision: "TAKEN" | "DECLINED", entry?: string, shares?: string) {
    const reasoning = window.prompt(`Why are you ${decision === "TAKEN" ? "taking" : "passing on"} ${card.ticker}?`) ?? "";
    setBusy(true);
    try {
      await decideRecommendation(
        card.id,
        decision,
        reasoning,
        entry && entry.trim() !== "" ? Number(entry) : null,
        shares && shares.trim() !== "" ? Number(shares) : null,
      );
      onDecided(card.id);
    } catch {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      {card.blackSwanActive && (
        <div className="rounded bg-losses/15 px-2 py-1 text-[11px] font-medium text-losses">
          ⚠ Black Swan active — confidence capped
        </div>
      )}

      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            <CompanyIcon ticker={card.ticker} logoUrl={logoUrl} title={card.ticker} size={24} />
            <span className="text-lg font-bold text-text-primary">{card.ticker}</span>
            <span className={`text-sm font-semibold ${card.direction === "BULLISH" ? "text-gains" : "text-losses"}`}>
              {card.actionLabel
                ? `${card.direction === "BULLISH" ? "▲" : "▼"} ${card.actionLabel}`
                : card.direction === "BULLISH"
                  ? "▲ Bullish"
                  : "▼ Bearish"}
            </span>
            {card.badge && (
              <span
                title={
                  card.badge === "FROZEN"
                    ? "Agent 5 is paused after a run of poor calls — under review."
                    : "Agent 5 hasn't built a track record yet — recommendations stay UNPROVEN until enough confirmed outcomes validate its win rate. It's honest, not a defect."
                }
                className={`cursor-help rounded px-1.5 py-0.5 text-[10px] font-medium ${card.badge === "FROZEN" ? "bg-losses/15 text-losses" : "bg-border/60 text-text-secondary"}`}
              >
                {card.badge}
              </span>
            )}
          </div>
          {card.sector && <p className="text-xs text-text-secondary">{card.sector}</p>}
        </div>
        <div className="text-right">
          {card.convictionScore != null ? (
            <>
              <p className="text-xs text-text-secondary" title="Evidence strength + breadth of independent sources + agreement, minus headwinds. 62+ is needed to act.">
                Conviction
              </p>
              <p className={`text-2xl font-bold leading-none tabular-nums ${card.direction === "BULLISH" ? "text-gains" : "text-losses"}`}>
                {card.convictionScore}
                <span className="text-xs font-medium text-text-secondary">/100</span>
              </p>
            </>
          ) : (
            <>
              <p className="text-xs text-text-secondary">Confidence</p>
              <p className="text-sm font-bold tabular-nums text-text-primary">
                {Math.round(card.confidence * 100)}%{card.confidenceCapped && <span className="text-warning"> *</span>}
              </p>
            </>
          )}
        </div>
      </div>

      {card.holdDays != null && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg bg-accent/10 px-3 py-2 text-xs">
          <span className="font-semibold text-accent">⏱ Hold about {card.holdDays} days</span>
          <span className="text-text-secondary">· {card.horizonLabel}</span>
          {card.reviewOn && <span className="text-text-secondary">· re-check by {formatDay(card.reviewOn)}</span>}
        </div>
      )}

      {card.priceGuidance && <PriceGuidanceStrip guidance={card.priceGuidance} />}

      {card.thesis && <p className="text-xs leading-relaxed text-text-primary">{card.thesis}</p>}

      {card.reasons.length > 0 && (
        <div>
          <p className="mb-1 text-[10px] font-medium uppercase tracking-wide text-text-secondary">Why</p>
          <ul className="flex flex-col gap-1">
            {card.reasons.map((r, i) => (
              <li key={i} className="flex gap-2 text-xs text-text-secondary">
                <span className="text-gains">✓</span>
                <span>{r}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {card.caveats.length > 0 && (
        <div>
          <p className="mb-1 text-[10px] font-medium uppercase tracking-wide text-warning">Watch out for</p>
          <ul className="flex flex-col gap-1">
            {card.caveats.map((c, i) => (
              <li key={i} className="flex gap-2 text-xs text-text-secondary">
                <span className="text-warning">!</span>
                <span>{c}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {(card.chart || card.deep || card.guidance || card.valuation) && (
        <div className="flex flex-col gap-1.5 rounded-lg border border-border px-3 py-2 text-xs">
          {card.chart && (
            <p className="text-text-secondary">
              <span className="font-medium text-text-primary">Chart · Agent 10:</span>{" "}
              <span className={card.chart.bias === "BULLISH" ? "text-gains" : card.chart.bias === "BEARISH" ? "text-losses" : ""}>
                {card.chart.bias.toLowerCase()} ({card.chart.score >= 0 ? "+" : ""}
                {card.chart.score.toFixed(2)})
              </span>{" "}
              — {card.chart.notes[0]}
            </p>
          )}
          {card.deep && (
            <p className="text-text-secondary">
              <span className="font-medium text-text-primary">Deep analysis · Agent 11:</span>{" "}
              <span className={card.deep.verdict === "WORTH_BUYING" ? "text-gains" : card.deep.verdict === "NOT_WORTH_BUYING" ? "text-losses" : "text-warning"}>
                {card.deep.verdictLabel}
              </span>
              {card.deep.holdDays != null && card.deep.verdict === "WORTH_BUYING" && <> · hold ~{card.deep.holdDays} days</>} · conviction{" "}
              {card.deep.conviction}/100{card.deep.headline ? ` — ${card.deep.headline}` : ""}
              {card.deep.atRisk && (
                <span className="ml-1 rounded bg-losses/15 px-1.5 py-0.5 text-[10px] font-semibold text-losses" title={card.deep.atRiskReason ?? undefined}>
                  ⚠ thesis at risk
                </span>
              )}
            </p>
          )}
          {(card.guidance || card.valuation) && (
            <p className="text-text-secondary">
              <span className="font-medium text-text-primary">Company & valuation · Agents 12/14:</span>{" "}
              {card.guidance && (
                <span className={card.guidance === "RAISED" ? "text-gains" : card.guidance === "LOWERED" ? "text-losses" : ""}>
                  earnings guidance {card.guidance.toLowerCase()}
                </span>
              )}
              {card.guidance && card.valuation && " · "}
              {card.valuation && (
                <span className={card.valuation === "CHEAP" ? "text-gains" : card.valuation === "RICH" ? "text-losses" : ""}>
                  price implies {card.valuation === "RICH" ? "more growth than delivered (rich)" : card.valuation === "CHEAP" ? "less growth than delivered (cheap)" : "about the growth delivered (fair)"}
                </span>
              )}
            </p>
          )}
        </div>
      )}

      {card.learned.length > 0 && (
        <div className="rounded-lg bg-accent/10 px-3 py-2">
          <p className="mb-1 text-[10px] font-medium uppercase tracking-wide text-accent">Learned from past trades · Agent 13</p>
          <ul className="flex flex-col gap-1">
            {card.learned.map((l, i) => (
              <li key={i} className="text-[11px] text-text-secondary">
                {l}
              </li>
            ))}
          </ul>
        </div>
      )}

      {card.exitPlan && <p className="text-[11px] italic text-text-secondary">{card.exitPlan}</p>}

      {/* Bull/bear probability bar */}
      <div>
        <div className="flex h-2.5 overflow-hidden rounded-full bg-losses/30">
          <div className="bg-gains" style={{ width: `${bull}%` }} />
        </div>
        <div className="mt-1 flex justify-between text-[11px] tabular-nums text-text-secondary">
          <span className="text-gains">{bull}% bull</span>
          <span title="Model odds after calibration — the conviction score above is the number to act on">
            odds
          </span>
          <span className="text-losses">{100 - bull}% bear</span>
        </div>
      </div>

      <div className="flex items-center justify-between">
        <SignalDots signals={card.signals} />
        {card.priceTarget != null && (
          <span className="text-xs text-text-secondary">
            Target <span className="font-medium text-text-primary tabular-nums">${card.priceTarget.toFixed(2)}</span>
          </span>
        )}
      </div>

      <div className="flex items-center justify-between">
        <button onClick={() => setOpen((o) => !o)} className="text-[11px] text-text-secondary underline">
          {open ? "Hide" : "Show"} diagnostic ({card.signals.length} signals)
        </button>
        <button
          onClick={() => setChatOpen(true)}
          className="rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent transition-colors hover:bg-accent/10"
        >
          Ask AI
        </button>
      </div>
      {open && (
        <ul className="flex flex-col gap-1.5 border-t border-border pt-2">
          {card.signals.map((s, i) => (
            <li key={i} className="flex items-start gap-2 text-xs">
              <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${dotColor(s.direction)}`} />
              <span className="text-text-secondary">
                <span className="font-medium text-text-primary">{s.agent}</span> · {s.direction.toLowerCase()} · w{s.weight.toFixed(2)}
                {s.rationale && <> — {s.rationale}</>}
              </span>
            </li>
          ))}
        </ul>
      )}

      <PersonaTakes recId={card.id} />

      <RecommendationDebate recommendationId={card.id} />

      {!decided && !takingOpen && (
        <div className="flex gap-2">
          <button disabled={busy} onClick={() => setTakingOpen(true)}
            className="rounded bg-gains/15 px-3 py-1.5 text-xs font-medium text-gains disabled:opacity-50">
            I took it
          </button>
          <button disabled={busy} onClick={() => decide("DECLINED")}
            className="rounded bg-border/60 px-3 py-1.5 text-xs font-medium text-text-secondary disabled:opacity-50">
            I&apos;ll pass
          </button>
        </div>
      )}

      {!decided && takingOpen && (
        <div className="flex flex-col gap-2 rounded-lg border border-border bg-background/50 p-3">
          <p className="text-[11px] text-text-secondary">Entry price and shares are optional — for your Trade Journal.</p>
          <div className="flex gap-2">
            <input
              type="number"
              inputMode="decimal"
              placeholder="Entry price"
              value={entryPrice}
              onChange={(e) => setEntryPrice(e.target.value)}
              className="w-full rounded border border-border bg-surface px-2 py-1 text-xs text-text-primary"
            />
            <input
              type="number"
              inputMode="decimal"
              placeholder="Shares"
              value={positionSize}
              onChange={(e) => setPositionSize(e.target.value)}
              className="w-full rounded border border-border bg-surface px-2 py-1 text-xs text-text-primary"
            />
          </div>
          <div className="flex gap-2">
            <button disabled={busy} onClick={() => decide("TAKEN", entryPrice, positionSize)}
              className="rounded bg-gains/15 px-3 py-1.5 text-xs font-medium text-gains disabled:opacity-50">
              Confirm
            </button>
            <button disabled={busy} onClick={() => setTakingOpen(false)}
              className="rounded bg-border/60 px-3 py-1.5 text-xs font-medium text-text-secondary disabled:opacity-50">
              Cancel
            </button>
          </div>
        </div>
      )}

      {chatOpen && (
        <RecommendationChat recommendationId={card.id} ticker={card.ticker} onClose={() => setChatOpen(false)} />
      )}
    </div>
  );
}

function PersonaTakes({ recId }: { recId: number }) {
  const [takes, setTakes] = useState<PersonaTake[] | null>(null);

  useEffect(() => {
    let active = true;
    const isWarming = (t: PersonaTake[]) =>
      t.length > 0 && t.every((x) => x.stance === "CAUTION" && x.rationale.includes("warming up"));
    let timer: ReturnType<typeof setTimeout>;
    const load = () =>
      getPersonas(recId)
        .then((t) => {
          if (!active) return;
          setTakes(t);
          if (isWarming(t)) timer = setTimeout(load, 15000); // poll until the warmer fills them in
        })
        .catch(() => active && setTakes([]));
    load();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [recId]);

  const warming =
    takes != null &&
    takes.length > 0 &&
    takes.every((t) => t.stance === "CAUTION" && t.rationale.includes("warming up"));

  const consensus = takes && takes.length > 0 && !warming ? summarizeConsensus(takes) : null;

  return (
    <div className="border-t border-border pt-2">
      <p className="mb-2 text-[10px] font-medium uppercase tracking-wide text-text-secondary">
        What the personas think
      </p>
      {takes === null || warming ? (
        <p className="text-[11px] italic text-text-secondary">Personas analyzing… (first look can take a moment)</p>
      ) : takes.length === 0 ? (
        <p className="text-[11px] text-text-secondary">No persona takes yet.</p>
      ) : (
        <>
          {consensus && (
            <p className="mb-1.5 text-[11px] font-medium" style={{ color: stanceColor(consensus.lean) }}>
              Consensus: {consensus.label}
            </p>
          )}
          <ul className="flex flex-col gap-1.5">
          {takes.map((t) => (
            <li key={t.key} className="flex items-start gap-2 text-xs">
              <span className="mt-px w-24 shrink-0 font-medium text-text-primary" title={t.lens}>
                {t.persona}
              </span>
              <span
                className="mt-px w-16 shrink-0 text-[10px] font-semibold uppercase tracking-wide"
                style={{ color: stanceColor(t.stance) }}
              >
                {t.stance}
              </span>
              <span className="text-text-secondary">{t.rationale}</span>
            </li>
          ))}
          </ul>
        </>
      )}
    </div>
  );
}

/**
 * One-line consensus over the four persona verdicts, derived locally from the stances (no extra model
 * call). The lean is the majority stance — AGREE/DISAGREE win over CAUTION on a tie, and an even
 * agree/disagree split reads as "split".
 */
function summarizeConsensus(takes: PersonaTake[]): { label: string; lean: string } {
  const agree = takes.filter((t) => t.stance === "AGREE").length;
  const disagree = takes.filter((t) => t.stance === "DISAGREE").length;
  const caution = takes.filter((t) => t.stance === "CAUTION").length;
  const parts = [
    agree > 0 ? `${agree} agree` : null,
    caution > 0 ? `${caution} caution` : null,
    disagree > 0 ? `${disagree} disagree` : null,
  ].filter(Boolean);
  let lean = "CAUTION";
  let verdict = "mixed";
  if (agree > disagree) {
    lean = "AGREE";
    verdict = agree === takes.length ? "unanimous support" : "leaning support";
  } else if (disagree > agree) {
    lean = "DISAGREE";
    verdict = disagree === takes.length ? "unanimous against" : "leaning against";
  } else if (agree === disagree && agree > 0) {
    verdict = "split";
  } else {
    verdict = "cautious";
  }
  return { label: `${verdict} (${parts.join(", ")})`, lean };
}

function prettyState(state: string): string {
  switch (state) {
    case "ACTIVE":
      return "Validated";
    case "FROZEN":
      return "Paused";
    default:
      return "Unproven"; // SHADOW / PROBATION
  }
}

function stanceColor(stance: string): string {
  return stance === "AGREE"
    ? "var(--color-gains)"
    : stance === "DISAGREE"
      ? "var(--color-losses)"
      : "var(--color-text-secondary)";
}

// Only agents that emit signals into a recommendation (5 is the recommender itself, 6 the cost governor).
const AGENT_SLOTS = [
  "agent-1", "agent-2", "agent-3", "agent-4", "agent-7", "agent-8", "agent-10", "agent-11",
];

function SignalDots({ signals }: { signals: SignalView[] }) {
  return (
    <div className="flex items-center gap-1.5" title="Agent signals: news, social, web, insider, calendar, macro, technical, dip-cause">
      {AGENT_SLOTS.map((slot) => {
        // "-" suffix so "agent-1" doesn't also match agent-10 / agent-11.
        const s = signals.find((x) => x.agent.startsWith(`${slot}-`));
        return <span key={slot} className={`h-2.5 w-2.5 rounded-full ${s ? dotColor(s.direction) : "bg-border"}`} />;
      })}
    </div>
  );
}

/** More decimals for a sub-$1 stock, so a real level doesn't display as a misleading $0.00. */
function money(n: number): string {
  const digits = n >= 1 ? 2 : n >= 0.01 ? 4 : 6;
  return `$${n.toFixed(digits)}`;
}

/**
 * What price to act at, for a human following this call: buy / sell / stop, each traced to the chart or a named
 * house rule (never an LLM guess). A CORE_HOLD call has no fixed sell price — {@code sellNote} says why.
 */
function PriceGuidanceStrip({ guidance: g }: { guidance: PriceGuidance }) {
  return (
    <div className="flex flex-col gap-1.5 rounded-lg border border-accent/25 bg-accent/[0.04] px-3 py-2 text-xs">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span>
          <span className="font-semibold text-text-primary">Buy</span>{" "}
          <span className="tabular-nums text-gains">
            <Sensitive>{money(g.buyPrice)}</Sensitive>
          </span>
        </span>
        {g.sellPrice != null ? (
          <span>
            <span className="font-semibold text-text-primary">Sell</span>{" "}
            <span className="tabular-nums text-losses">
              <Sensitive>{money(g.sellPrice)}</Sensitive>
            </span>
          </span>
        ) : (
          <span className="font-semibold text-accent">Core holding — no fixed sell target</span>
        )}
        <span>
          <span className="font-semibold text-text-primary">Stop</span>{" "}
          <span className="tabular-nums text-warning">
            <Sensitive>{money(g.stopPrice)}</Sensitive>
          </span>
        </span>
      </div>
      <p className="text-[11px] leading-relaxed text-text-secondary">
        {g.buyNote}
        {g.sellPrice != null && <> · target: {g.sellNote}</>}
        {g.sellPrice == null && <> {g.sellNote}</>}
        {" "}· stop: {g.stopNote}
      </p>
    </div>
  );
}

function formatDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function dotColor(direction: string): string {
  if (direction === "BULLISH") return "bg-gains";
  if (direction === "BEARISH") return "bg-losses";
  return "bg-text-secondary";
}
