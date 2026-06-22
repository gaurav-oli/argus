"use client";

import { useEffect } from "react";
import { motion, useReducedMotion } from "motion/react";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { Sensitive } from "@/features/privacy/Sensitive";
import { useConfetti } from "@/lib/useConfetti";
import { useMounted } from "@/lib/useMounted";
import { pct, portfolio, trend, usdPrecise } from "@/lib/mockData";

/**
 * Hero card: total value counts up, the day's move animates in with up/down
 * colour + arrow, a gradient area sparkline sits behind, and a small confetti
 * burst fires once when the day is green. Motion-sensitive users opt out (the
 * confetti lib + the global reduced-motion reset both honour the OS setting).
 */
export function PortfolioHero() {
  const fire = useConfetti();
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const up = portfolio.dayChange >= 0;
  const accent = up ? "var(--chart-gains)" : "var(--chart-losses)";

  useEffect(() => {
    if (up) {
      const t = setTimeout(() => fire(), 700);
      return () => clearTimeout(t);
    }
  }, [up, fire]);

  return (
    <div className="relative flex h-full flex-col justify-between">
      {/* sparkline backdrop */}
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-1/2 opacity-60">
        {mounted && (
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={trend} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
            <defs>
              <linearGradient id="heroSpark" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={accent} stopOpacity={0.35} />
                <stop offset="100%" stopColor={accent} stopOpacity={0} />
              </linearGradient>
            </defs>
            <Area
              type="monotone"
              dataKey="value"
              stroke={accent}
              strokeWidth={2}
              fill="url(#heroSpark)"
              isAnimationActive={false}
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
        )}
      </div>

      <div className="relative">
        <p className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Total Portfolio Value</p>
        <Sensitive className="mt-1 block text-5xl font-bold tracking-tight">
          <AnimatedNumber
            value={portfolio.totalValue}
            format={usdPrecise}
            className="mt-1 block font-mono text-5xl font-bold tracking-tight text-text-primary tabular-nums"
          />
        </Sensitive>
      </div>

      <motion.div
        initial={reduce ? false : { opacity: 0, x: -8 }}
        animate={{ opacity: 1, x: 0 }}
        transition={reduce ? { duration: 0 } : { delay: 0.5, type: "spring", stiffness: 200, damping: 20 }}
        className="relative mt-4 flex items-center gap-3"
      >
        <span
          className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-semibold"
          style={{ color: accent, backgroundColor: `color-mix(in srgb, ${accent} 14%, transparent)` }}
        >
          {/* Arrow + figure both masked: the ▲/▼ alone would leak the gain/loss direction. */}
          <Sensitive className="text-sm font-semibold">
            <span className="inline-flex items-center gap-1.5">
              <motion.span
                animate={reduce ? undefined : { y: up ? [-1, -3, -1] : [1, 3, 1] }}
                transition={reduce ? undefined : { repeat: Infinity, duration: 2, ease: "easeInOut" }}
              >
                {up ? "▲" : "▼"}
              </motion.span>
              {usdPrecise(Math.abs(portfolio.dayChange))} ({pct(portfolio.dayChangePct)})
            </span>
          </Sensitive>
        </span>
        <span className="text-xs text-text-secondary">today</span>
      </motion.div>
    </div>
  );
}
