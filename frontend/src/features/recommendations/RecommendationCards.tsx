"use client";

import {
  decideRecommendation,
  getGraduation,
  getPersonas,
  getRecommendations,
  type GraduationSummary,
  type PersonaTake,
  type RecommendationCard as Card,
  type SignalView,
} from "@/lib/apiClient";
import { RecommendationChat } from "@/features/conversation/RecommendationChat";
import { Carousel } from "@/components/ui/Carousel";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Skeleton } from "@/components/ui/Skeleton";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { useEffect, useMemo, useState } from "react";

/**
 * Probability Forecast Cards (Epic 6 — Agent 5). Weather-style cards from /api/recommendations:
 * direction, a bull/bear probability bar, confidence (with the Black-Swan cap), 7-agent signal dots,
 * an expandable diagnostic (Story 6.2), and Taken/Declined actions that snapshot the decision (6.7).
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
    return null; // nothing to show yet — Agent 5 produces these on its trigger/review
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
              {card.direction === "BULLISH" ? "▲ Bullish" : "▼ Bearish"}
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
          {card.horizon && <p className="text-xs text-text-secondary">Horizon: {card.horizon}</p>}
        </div>
        <div className="text-right">
          <p className="text-xs text-text-secondary">Confidence</p>
          <p className="text-sm font-bold tabular-nums text-text-primary">
            {Math.round(card.confidence * 100)}%{card.confidenceCapped && <span className="text-warning"> *</span>}
          </p>
        </div>
      </div>

      {/* Bull/bear probability bar */}
      <div>
        <div className="flex h-2.5 overflow-hidden rounded-full bg-losses/30">
          <div className="bg-gains" style={{ width: `${bull}%` }} />
        </div>
        <div className="mt-1 flex justify-between text-[11px] tabular-nums text-text-secondary">
          <span className="text-gains">{bull}% bull</span>
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

const AGENT_SLOTS = ["agent-1", "agent-2", "agent-3", "agent-4", "agent-5", "agent-6", "agent-7"];

function SignalDots({ signals }: { signals: SignalView[] }) {
  return (
    <div className="flex items-center gap-1.5" title="7-agent signals">
      {AGENT_SLOTS.map((slot) => {
        const s = signals.find((x) => x.agent.startsWith(slot));
        return <span key={slot} className={`h-2.5 w-2.5 rounded-full ${s ? dotColor(s.direction) : "bg-border"}`} />;
      })}
    </div>
  );
}

function dotColor(direction: string): string {
  if (direction === "BULLISH") return "bg-gains";
  if (direction === "BEARISH") return "bg-losses";
  return "bg-text-secondary";
}
