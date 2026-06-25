"use client";

import { useEffect, useState } from "react";
import { useReducedMotion } from "motion/react";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { getValueHistory, type ValuePoint } from "@/lib/apiClient";
import { usd } from "@/lib/format";
import { useMounted } from "@/lib/useMounted";

type TooltipProps = {
  active?: boolean;
  payload?: { value: number; payload: ValuePoint }[];
};

function ChartTooltip({ active, payload }: TooltipProps) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-border bg-surface/95 px-3 py-2 shadow-xl backdrop-blur">
      <p className="text-[10px] uppercase tracking-wider text-text-secondary">{payload[0].payload.date}</p>
      <p className="font-mono text-sm font-semibold text-text-primary">{usd(payload[0].value)}</p>
    </div>
  );
}

/** 30-day portfolio value (Story 3.6, /api/portfolio/value-history) — gradient area, animated draw-in. */
export function PortfolioTrendChart() {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const [series, setSeries] = useState<ValuePoint[] | null>(null);

  useEffect(() => {
    let active = true;
    getValueHistory("1M").then((s) => active && setSeries(s)).catch(() => active && setSeries([]));
    return () => {
      active = false;
    };
  }, []);

  const change =
    series && series.length > 1
      ? ((series[series.length - 1].totalValueCad - series[0].totalValueCad) /
          (series[0].totalValueCad || 1)) *
        100
      : null;
  const up = (change ?? 0) >= 0;

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">30-Day Trend</h3>
        {change != null && (
          <span
            className="rounded-full px-2 py-0.5 text-[11px] font-medium"
            style={{
              color: up ? "var(--color-gains)" : "var(--color-losses)",
              backgroundColor: `color-mix(in srgb, ${up ? "var(--color-gains)" : "var(--color-losses)"} 12%, transparent)`,
            }}
          >
            {up ? "+" : ""}
            {change.toFixed(1)}%
          </span>
        )}
      </div>
      <div className="mt-3 h-48 w-full">
        {series && series.length > 1 ? (
          mounted && (
            <ResponsiveContainer width="100%" height="100%" minHeight={180}>
              <AreaChart data={series} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
                <defs>
                  <linearGradient id="trendGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--chart-accent)" stopOpacity={0.4} />
                    <stop offset="100%" stopColor="var(--chart-accent)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" vertical={false} />
                <XAxis dataKey="date" hide />
                <YAxis domain={["dataMin - 8000", "dataMax + 8000"]} hide />
                <Tooltip content={<ChartTooltip />} cursor={{ stroke: "var(--chart-axis)", strokeOpacity: 0.4 }} />
                <Area
                  type="monotone"
                  dataKey="totalValueCad"
                  stroke="var(--chart-accent)"
                  strokeWidth={2.5}
                  fill="url(#trendGrad)"
                  isAnimationActive={!reduce}
                  animationDuration={1100}
                  animationEasing="ease-out"
                  dot={false}
                  activeDot={{ r: 4, fill: "var(--chart-accent)", stroke: "var(--color-background)", strokeWidth: 2 }}
                />
              </AreaChart>
            </ResponsiveContainer>
          )
        ) : (
          <div className="flex h-full items-center justify-center text-center text-xs text-text-secondary">
            {series === null ? "Loading…" : "No history yet — value is tracked daily once you hold positions."}
          </div>
        )}
      </div>
    </div>
  );
}
