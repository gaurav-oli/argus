"use client";

import { useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { Sensitive } from "@/features/privacy/Sensitive";
import {
  getPortfolioValue,
  getValueHistory,
  type PortfolioSnapshot,
  type ValuePoint,
} from "@/lib/apiClient";
import { pct, usdPrecise } from "@/lib/format";

/**
 * Hero card — real total portfolio value (Story 3.4, /api/portfolio/value) counting up, total P&L
 * with up/down colour, and a value-history sparkline (Story 3.6) behind. Empty until a statement is
 * imported. Motion-sensitive users opt out via the global reduced-motion reset.
 */
export function PortfolioHero() {
  const reduce = useReducedMotion();
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);
  const [history, setHistory] = useState<ValuePoint[]>([]);

  useEffect(() => {
    let active = true;
    getPortfolioValue().then((s) => active && setSnap(s)).catch(() => {});
    getValueHistory("1M").then((h) => active && setHistory(h)).catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const value = snap?.totalValueCad ?? 0;
  const pnl = snap?.totalPnlCad ?? null;
  const cost = snap?.totalCostCad ?? null;
  const pnlPct = pnl != null && cost ? (pnl / cost) * 100 : null;
  const up = (pnl ?? 0) >= 0;
  const accent = up ? "var(--chart-gains)" : "var(--chart-losses)";
  const hasHoldings = (snap?.positions.length ?? 0) > 0;

  return (
    <div className="relative flex h-full flex-col justify-between">
      {/* value-history sparkline backdrop */}
      {history.length > 1 && (
        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-1/2 opacity-60">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={history} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
              <defs>
                <linearGradient id="heroSpark" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={accent} stopOpacity={0.35} />
                  <stop offset="100%" stopColor={accent} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area
                type="monotone"
                dataKey="totalValueCad"
                stroke={accent}
                strokeWidth={2}
                fill="url(#heroSpark)"
                isAnimationActive={false}
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}

      <div className="relative">
        <p className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Total Portfolio Value</p>
        <Sensitive className="mt-1 block text-5xl font-bold tracking-tight">
          <AnimatedNumber
            value={value}
            format={usdPrecise}
            className="mt-1 block font-mono text-5xl font-bold tracking-tight text-text-primary tabular-nums"
          />
        </Sensitive>
      </div>

      {hasHoldings && pnl != null ? (
        <motion.div
          initial={reduce ? false : { opacity: 0, x: -8 }}
          animate={{ opacity: 1, x: 0 }}
          transition={reduce ? { duration: 0 } : { delay: 0.4, type: "spring", stiffness: 200, damping: 20 }}
          className="relative mt-4 flex items-center gap-3"
        >
          <span
            className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-semibold"
            style={{ color: accent, backgroundColor: `color-mix(in srgb, ${accent} 14%, transparent)` }}
          >
            <Sensitive className="text-sm font-semibold">
              <span className="inline-flex items-center gap-1.5">
                <span>{up ? "▲" : "▼"}</span>
                {usdPrecise(Math.abs(pnl))}
                {pnlPct != null && ` (${pct(pnlPct)})`}
              </span>
            </Sensitive>
          </span>
          <span className="text-xs text-text-secondary">total P&amp;L</span>
        </motion.div>
      ) : (
        <p className="relative mt-4 text-xs text-text-secondary">
          No holdings yet — import a statement to track your portfolio.
        </p>
      )}
    </div>
  );
}
