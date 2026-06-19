"use client";

import { motion } from "motion/react";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { pct, topMovers } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

function Sparkline({ data, up }: { data: number[]; up: boolean }) {
  const mounted = useMounted();
  const series = data.map((v, i) => ({ i, v }));
  const color = up ? "#00FF88" : "#FF3B5C";
  const id = `spark-${up ? "up" : "down"}`;
  if (!mounted) return <div className="h-8 w-16 shrink-0" />;
  return (
    <ResponsiveContainer width={64} height={32}>
      <AreaChart data={series} margin={{ top: 2, right: 0, bottom: 2, left: 0 }}>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.4} />
            <stop offset="100%" stopColor={color} stopOpacity={0} />
          </linearGradient>
        </defs>
        <Area type="monotone" dataKey="v" stroke={color} strokeWidth={1.5} fill={`url(#${id})`} dot={false} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  );
}

/** Holdings on the move — each row springs in with a mini sparkline + delta. */
export function TopMovers() {
  return (
    <div>
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Top Movers</h3>
      <ul className="mt-3 space-y-1">
        {topMovers.map((m, i) => {
          const up = m.changePct >= 0;
          return (
            <motion.li
              key={m.symbol}
              initial={{ opacity: 0, x: 12 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.15 + i * 0.08, type: "spring", stiffness: 200, damping: 22 }}
              whileHover={{ backgroundColor: "rgba(255,255,255,0.03)" }}
              className="flex items-center gap-3 rounded-xl px-2 py-2"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/[0.04] font-mono text-[10px] font-bold text-text-primary">
                {m.symbol}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-text-primary">{m.name}</p>
                <p className="font-mono text-xs text-text-secondary tabular-nums">${m.price.toFixed(2)}</p>
              </div>
              <Sparkline data={m.spark} up={up} />
              <span
                className="w-16 text-right font-mono text-sm font-semibold tabular-nums"
                style={{ color: up ? "#00FF88" : "#FF3B5C" }}
              >
                {pct(m.changePct)}
              </span>
            </motion.li>
          );
        })}
      </ul>
    </div>
  );
}
