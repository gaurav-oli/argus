"use client";

import {
  decideRecommendation,
  getRecommendations,
  type RecommendationCard as Card,
  type SignalView,
} from "@/lib/apiClient";
import { RecommendationChat } from "@/features/conversation/RecommendationChat";
import { Skeleton } from "@/components/ui/Skeleton";
import { useEffect, useState } from "react";

/**
 * Probability Forecast Cards (Epic 6 — Agent 5). Weather-style cards from /api/recommendations:
 * direction, a bull/bear probability bar, confidence (with the Black-Swan cap), 7-agent signal dots,
 * an expandable diagnostic (Story 6.2), and Taken/Declined actions that snapshot the decision (6.7).
 */
export function RecommendationCards() {
  const [cards, setCards] = useState<Card[] | null>(null);

  useEffect(() => {
    let active = true;
    getRecommendations()
      .then((c) => active && setCards(c))
      .catch(() => active && setCards([]));
    return () => {
      active = false;
    };
  }, []);

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
      <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
        Recommendations · Agent 5
      </h2>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {cards.map((c) => (
          <ForecastCard key={c.id} card={c} onDecided={(id) => setCards((cs) => cs?.filter((x) => x.id !== id) ?? null)} />
        ))}
      </div>
    </section>
  );
}

function ForecastCard({ card, onDecided }: { card: Card; onDecided: (id: number) => void }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  const bull = Math.round(card.bullProbability * 100);
  const decided = card.status === "TAKEN" || card.status === "DECLINED";

  async function decide(decision: "TAKEN" | "DECLINED") {
    const reasoning = window.prompt(`Why are you ${decision === "TAKEN" ? "taking" : "passing on"} ${card.ticker}?`) ?? "";
    setBusy(true);
    try {
      await decideRecommendation(card.id, decision, reasoning);
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
            <span className="text-lg font-bold text-text-primary">{card.ticker}</span>
            <span className={`text-sm font-semibold ${card.direction === "BULLISH" ? "text-gains" : "text-losses"}`}>
              {card.direction === "BULLISH" ? "▲ Bullish" : "▼ Bearish"}
            </span>
            {card.badge && (
              <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${card.badge === "FROZEN" ? "bg-losses/15 text-losses" : "bg-warning/15 text-warning"}`}>
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

      {!decided && (
        <div className="flex gap-2">
          <button disabled={busy} onClick={() => decide("TAKEN")}
            className="rounded bg-gains/15 px-3 py-1.5 text-xs font-medium text-gains disabled:opacity-50">
            I took it
          </button>
          <button disabled={busy} onClick={() => decide("DECLINED")}
            className="rounded bg-border/60 px-3 py-1.5 text-xs font-medium text-text-secondary disabled:opacity-50">
            I&apos;ll pass
          </button>
        </div>
      )}

      {chatOpen && (
        <RecommendationChat recommendationId={card.id} ticker={card.ticker} onClose={() => setChatOpen(false)} />
      )}
    </div>
  );
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
