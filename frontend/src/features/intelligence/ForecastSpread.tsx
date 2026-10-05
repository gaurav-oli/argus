"use client";

import { useReducedMotion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import { AsciiBar } from "@/components/terminal/AsciiBar";
import type { ChartBar, PriceGuidance } from "@/lib/apiClient";
import { blockFor, buildSpread, dailyVolatility, pct, type Spread, type SpreadMarker } from "@/lib/forecastSpread";
import { cn } from "@/lib/utils";

export interface ForecastInput {
  bullProbability: number;
  direction: "BULLISH" | "BEARISH";
  holdDays: number;
  priceGuidance: PriceGuidance | null;
}

const MARK: Record<SpreadMarker["kind"], { glyph: string; cls: string; label: string }> = {
  stop: { glyph: "S", cls: "text-losses", label: "stop" },
  entry: { glyph: "│", cls: "text-text-primary", label: "entry" },
  target: { glyph: "T", cls: "text-gains", label: "target" },
};

/**
 * Probability Weather, terminal edition — the call's model odds drawn as an ASCII histogram of the
 * horizon returns they imply (see lib/forecastSpread for the math and why it adds no new number).
 * The side the call bets on is lit (green for a bullish call, red for a bearish one), the other side
 * dims, and the stop / entry / target prices sit on a marker rail underneath. Columns grow upward in
 * eighth-block steps on open, like a CRT painting rows; reduced motion shows it complete.
 *
 * Renders nothing usable without real inputs: no curve when the ticker has too little price history,
 * and the caller only mounts it for actionable calls (a WATCH has no odds).
 */
export function ForecastSpread({ ticker, forecast, candles }: { ticker: string; forecast: ForecastInput; candles: ChartBar[] | null }) {
  const reduce = useReducedMotion();
  const sigmaDaily = useMemo(() => (candles ? dailyVolatility(candles.map((c) => c.close)) : null), [candles]);

  // Memoised on primitives, so a re-created `forecast` object with the same numbers keeps the same
  // spread (and doesn't restart the grow animation).
  const { bullProbability, direction, holdDays } = forecast;
  const entryPrice = forecast.priceGuidance?.buyPrice ?? null;
  const targetPrice = forecast.priceGuidance?.sellPrice ?? null;
  const stopPrice = forecast.priceGuidance?.stopPrice ?? null;
  const spread = useMemo(
    () =>
      sigmaDaily == null
        ? null
        : buildSpread({ bullProbability, direction, holdDays, sigmaDaily, entryPrice, targetPrice, stopPrice }),
    [sigmaDaily, bullProbability, direction, holdDays, entryPrice, targetPrice, stopPrice],
  );

  // Grow animation: `stage` eighth-blocks revealed so far (keyed to the spread, so a new ticker regrows).
  const maxLevel = spread ? spread.rows * 8 : 0;
  const [grow, setGrow] = useState<{ key: Spread | null; stage: number }>({ key: null, stage: 0 });
  const stage = reduce ? maxLevel : grow.key === spread ? grow.stage : 0;
  useEffect(() => {
    if (reduce || !spread) return;
    const id = setInterval(() => {
      setGrow((g) => {
        const s = g.key === spread ? g.stage : 0;
        if (s >= maxLevel) {
          clearInterval(id);
          return g;
        }
        return { key: spread, stage: Math.min(maxLevel, s + 2) };
      });
    }, 28);
    return () => clearInterval(id);
  }, [spread, maxLevel, reduce]);

  const bull = Math.round(forecast.bullProbability * 100);
  const bullish = forecast.direction === "BULLISH";
  const n = spread?.columns.length ?? 0;
  const rail = Array.from({ length: n }, () => null as SpreadMarker | null);
  spread?.markers.forEach((m) => {
    // Entry is drawn first; stop/target win a shared column so they stay visible.
    if (rail[m.column] == null || m.kind !== "entry") rail[m.column] = m;
  });

  return (
    <section className="glass mt-4 p-4 font-mono text-xs" aria-label={`${ticker} forecast spread`}>
      <h3 className="mb-3 text-[11px]">forecast · {forecast.holdDays}-day horizon</h3>

      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <span className="font-display text-5xl leading-none text-accent">{spread ? spread.betSidePercent : bullish ? bull : 100 - bull}%</span>
        <span className="text-sm text-text-primary">
          {bullish ? "bull" : "bear"} odds on this call · model-derived
        </span>
      </div>
      <AsciiBar percent={bull} cells={40} label={`${bull}% bull, ${100 - bull}% bear`} className="mt-3 text-sm tracking-[-0.05em]" />
      <div className="mt-1 flex justify-between text-[11px] text-text-secondary">
        <span className="text-gains">{bull}% bull</span>
        <span className="text-losses">{100 - bull}% bear</span>
      </div>

      {spread == null ? (
        <p className="mt-4 text-text-secondary">
          Not enough price history for {ticker} yet to draw the spread. The odds above are the model&apos;s.
        </p>
      ) : (
        <figure className="mt-5">
          <div
            role="img"
            aria-label={`Illustrative ${forecast.holdDays}-day returns centred on ${pct(spread.median)}, one-sigma ±${(spread.sigma * 100).toFixed(1)}%, with ${spread.betSidePercent}% on the call's side.`}
            className="overflow-x-auto"
          >
            <div aria-hidden className="inline-block min-w-full leading-[1.05] text-[15px] tracking-[-0.06em]">
              {Array.from({ length: spread.rows }, (_, k) => spread.rows - 1 - k).map((row) => (
                <div key={row} className="whitespace-pre">
                  {spread.columns.map((c, i) => (
                    <span
                      key={i}
                      className={cn(
                        c.side === "zero" ? "text-text-secondary" : c.side === "up" ? "text-gains" : "text-losses",
                        !c.onBetSide && c.side !== "zero" && "opacity-35",
                      )}
                    >
                      {blockFor(Math.min(c.level, stage), row)}
                    </span>
                  ))}
                </div>
              ))}
              <div className="whitespace-pre border-t border-[var(--hairline)] pt-0.5">
                {rail.map((m, i) => (
                  <span key={i} className={m ? MARK[m.kind].cls : undefined}>
                    {m ? MARK[m.kind].glyph : " "}
                  </span>
                ))}
              </div>
            </div>
          </div>
          <div className="mt-1 flex justify-between text-[11px] text-text-secondary">
            <span>{pct(-spread.range, 0)}</span>
            <span>0%</span>
            <span>{pct(spread.range, 0)}</span>
          </div>

          <figcaption className="mt-3 space-y-1 text-[11px] leading-relaxed text-text-secondary">
            <p className="flex flex-wrap gap-x-4">
              {spread.markers
                .filter((m) => m.kind !== "entry")
                .map((m) => (
                  <span key={m.kind}>
                    <span className={MARK[m.kind].cls}>{MARK[m.kind].glyph}</span> {MARK[m.kind].label} {pct(m.ret)}
                  </span>
                ))}
              <span>
                centre {pct(spread.median)} · ±{(spread.sigma * 100).toFixed(1)}% one-sigma
              </span>
            </p>
            <p>
              The odds are the model&apos;s. The shape treats them as the chance of finishing above entry and spreads
              that over {ticker}&apos;s last 60 trading days of volatility. It illustrates the odds; it isn&apos;t a
              second forecast.
            </p>
          </figcaption>
        </figure>
      )}
    </section>
  );
}

