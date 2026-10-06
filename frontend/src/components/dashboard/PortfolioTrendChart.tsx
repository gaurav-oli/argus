"use client";

import { useState } from "react";
import { useReducedMotion } from "motion/react";
import { Area, AreaChart, ResponsiveContainer, Tooltip } from "recharts";
import { getValueHistory, type ValuePoint } from "@/lib/apiClient";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { DemoPlaceholder } from "@/features/privacy/DemoPlaceholder";
import { usd } from "@/lib/format";
import { useMounted } from "@/lib/useMounted";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

type TooltipProps = {
  active?: boolean;
  payload?: { value: number; payload: ValuePoint }[];
};

function ChartTooltip({ active, payload }: TooltipProps) {
  if (!active || !payload?.length) return null;
  return (
    <div className="border border-border bg-surface px-3 py-2">
      <p className="text-[10px] uppercase tracking-wider text-text-secondary">{payload[0].payload.date}</p>
      <p className="font-serif-editorial text-sm text-text-primary">{usd(payload[0].value)}</p>
    </div>
  );
}

/** 30-day portfolio value (Story 3.6, /api/portfolio/value-history) — a fine single-stroke line, no fill. */
export function PortfolioTrendChart() {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const { demoMode } = useDemoMode();
  const [series, setSeries] = useState<ValuePoint[] | null>(null);

  useAutoRefresh(() =>
    getValueHistory("1M")
      .then(setSeries)
      .catch(() => setSeries((prev) => prev ?? [])),
  );

  const change =
    series && series.length > 1
      ? ((series[series.length - 1].totalValueCad - series[0].totalValueCad) /
          (series[0].totalValueCad || 1)) *
        100
      : null;
  const up = (change ?? 0) >= 0;

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">30-Day Trend</h3>
        {change != null && (
          <span className="text-[11px] font-medium" style={{ color: up ? "var(--color-gains)" : "var(--color-losses)" }}>
            {up ? "+" : ""}
            {change.toFixed(1)}%
          </span>
        )}
      </div>
      <div className="mt-3 h-48 w-full">
        {demoMode ? (
          <DemoPlaceholder label="Hidden while Demo Mode is on" />
        ) : series && series.length > 1 ? (
          mounted && (
            <ResponsiveContainer width="100%" height="100%" minHeight={180}>
              <AreaChart data={series} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
                <Tooltip content={<ChartTooltip />} cursor={{ stroke: "var(--chart-axis)", strokeOpacity: 0.4 }} />
                <Area
                  type="monotone"
                  dataKey="totalValueCad"
                  stroke="var(--chart-accent)"
                  strokeWidth={1}
                  fill="none"
                  isAnimationActive={!reduce}
                  animationDuration={1100}
                  animationEasing="ease-out"
                  dot={false}
                  activeDot={{ r: 3, fill: "var(--chart-accent)", stroke: "var(--color-background)", strokeWidth: 1.5 }}
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
