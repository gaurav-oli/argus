"use client";

import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { trend, usd } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

type TooltipProps = {
  active?: boolean;
  payload?: { value: number }[];
  label?: number;
};

function ChartTooltip({ active, payload, label }: TooltipProps) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-white/10 bg-background/95 px-3 py-2 shadow-xl backdrop-blur">
      <p className="text-[10px] uppercase tracking-wider text-text-secondary">Day {label}</p>
      <p className="font-mono text-sm font-semibold text-text-primary">{usd(payload[0].value)}</p>
    </div>
  );
}

/** 30-day portfolio value — gradient area chart with an animated draw-in. */
export function PortfolioTrendChart() {
  const mounted = useMounted();
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">30-Day Trend</h3>
        <span className="rounded-full bg-gains/10 px-2 py-0.5 text-[11px] font-medium text-gains">+7.8%</span>
      </div>
      <div className="mt-3 h-48 w-full">
        {mounted && (
        <ResponsiveContainer width="100%" height="100%" minHeight={180}>
          <AreaChart data={trend} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
            <defs>
              <linearGradient id="trendGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#00D4FF" stopOpacity={0.4} />
                <stop offset="100%" stopColor="#00D4FF" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" vertical={false} />
            <XAxis dataKey="day" hide />
            <YAxis domain={["dataMin - 8000", "dataMax + 8000"]} hide />
            <Tooltip content={<ChartTooltip />} cursor={{ stroke: "rgba(255,255,255,0.15)" }} />
            <Area
              type="monotone"
              dataKey="value"
              stroke="#00D4FF"
              strokeWidth={2.5}
              fill="url(#trendGrad)"
              isAnimationActive={false}
              dot={false}
              activeDot={{ r: 4, fill: "#00D4FF", stroke: "#0A0A0F", strokeWidth: 2 }}
            />
          </AreaChart>
        </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
