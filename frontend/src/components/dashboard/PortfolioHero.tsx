"use client";

import { useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { Sensitive } from "@/features/privacy/Sensitive";
import { getPortfolioValue, getValueHistory, type PortfolioSnapshot, type ValuePoint } from "@/lib/apiClient";
import { pct, usdPrecise } from "@/lib/format";

/**
 * Hero — real total portfolio value (Story 3.4, /api/portfolio/value) counting up in a serif
 * display face, total P&L as plain colored text (no badge), and a fine single-stroke value-history
 * line standing in for an underline rule (Story 3.6). Empty until a statement is imported.
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
      <div className="relative">
        <p className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Portfolio Value</p>
        <Sensitive className="mt-1 block text-5xl font-normal tracking-tight">
          <AnimatedNumber
            value={value}
            format={usdPrecise}
            className="font-serif-editorial mt-1 block text-5xl font-normal tracking-tight text-text-primary"
          />
        </Sensitive>

        {/* fine single-stroke trend line standing in for an underline rule */}
        {history.length > 1 ? (
          <div className="mt-2 h-10 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={history} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
                <Area
                  type="monotone"
                  dataKey="totalValueCad"
                  stroke="var(--c-accent)"
                  strokeWidth={1}
                  fill="none"
                  isAnimationActive={false}
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="mt-4 h-px w-full bg-[var(--hairline)]" />
        )}
      </div>

      {hasHoldings && pnl != null ? (
        <motion.div
          initial={reduce ? false : { opacity: 0, x: -8 }}
          animate={{ opacity: 1, x: 0 }}
          transition={reduce ? { duration: 0 } : { delay: 0.4, type: "spring", stiffness: 200, damping: 20 }}
          className="relative mt-4 flex items-center gap-2 text-sm font-medium"
          style={{ color: accent }}
        >
          <Sensitive className="text-sm font-medium">
            <span>
              {up ? "▲" : "▼"} {usdPrecise(Math.abs(pnl))}
              {pnlPct != null && ` (${pct(pnlPct)})`}
            </span>
          </Sensitive>
          <span className="text-text-secondary">total P&amp;L</span>
        </motion.div>
      ) : (
        <p className="relative mt-4 text-xs text-text-secondary">
          No holdings yet — import a statement to track your portfolio.
        </p>
      )}
    </div>
  );
}
