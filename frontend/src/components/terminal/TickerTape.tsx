"use client";

import { useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { getPortfolioValue, type PositionValue } from "@/lib/apiClient";

type Quote = { ticker: string; price: string; change: string; up: boolean | null };

function toQuote(p: PositionValue): Quote {
  const pct = p.dayPnlPercent;
  return {
    ticker: p.ticker,
    price: p.price == null ? "—" : p.price.toFixed(2),
    change: pct == null ? "" : `${pct >= 0 ? "+" : ""}${pct.toFixed(2)}%`,
    up: pct == null ? null : pct >= 0,
  };
}

/**
 * Terminal Noir ticker tape — the signed-in person's own holdings scrolling across the top of every
 * page with live price and day change. Refreshes every minute. The list is doubled so the -50%
 * translate loops seamlessly. Shows prices and percentages only, never quantities or values; hidden
 * entirely in Demo Mode (which hides the portfolio) and when there are no holdings yet. Under
 * reduced motion the tape holds still and scrolls by hand.
 */
export function TickerTape() {
  const reduce = useReducedMotion();
  const { demoMode } = useDemoMode();
  const [quotes, setQuotes] = useState<Quote[]>([]);

  useEffect(() => {
    let active = true;
    const load = () =>
      getPortfolioValue()
        .then((s) => {
          if (!active) return;
          const sorted = [...s.positions].sort((a, b) => (b.weightPercent ?? 0) - (a.weightPercent ?? 0));
          setQuotes(sorted.map(toQuote));
        })
        .catch(() => {});
    load();
    const id = setInterval(load, 60_000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, []);

  if (demoMode || quotes.length === 0) return null;

  // Long enough that one copy always overflows the viewport, then doubled for the seamless loop.
  const base = quotes.length < 8 ? [...quotes, ...quotes, ...quotes] : quotes;
  const loop = [...base, ...base];
  const seconds = Math.max(30, base.length * 4);

  return (
    <div
      className={`shrink-0 border-b border-[var(--glass-border)] bg-[#0d0c09] font-mono text-xs ${reduce ? "overflow-x-auto" : "overflow-hidden"}`}
      aria-label="Your holdings, live prices"
    >
      <div
        className="inline-flex gap-8 whitespace-nowrap py-1.5 pl-4"
        style={reduce ? undefined : { animation: `term-tape ${seconds}s linear infinite` }}
      >
        {loop.map((q, i) => (
          <span key={`${q.ticker}-${i}`} aria-hidden={i >= base.length || undefined}>
            <span className="text-accent">{q.ticker}</span> <span className="text-text-primary">{q.price}</span>{" "}
            <span className={q.up == null ? "text-text-secondary" : q.up ? "text-gains" : "text-losses"}>
              {q.up == null ? "" : q.up ? "▲" : "▼"}
              {q.change}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
