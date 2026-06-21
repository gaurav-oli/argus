"use client";

import { useReducedMotion } from "motion/react";
import { PolarAngleAxis, RadialBar, RadialBarChart, ResponsiveContainer } from "recharts";
import { gauges } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

function Gauge({ value, max, label, display, color }: (typeof gauges)[number]) {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const data = [{ value: Math.max(0, Math.min(value, max)), fill: color }];
  return (
    <div className="flex flex-col items-center">
      <div className="relative h-24 w-24">
        {mounted && (
          <ResponsiveContainer width="100%" height="100%">
            <RadialBarChart innerRadius="72%" outerRadius="100%" data={data} startAngle={215} endAngle={-35}>
              <PolarAngleAxis type="number" domain={[0, max]} tick={false} />
              <RadialBar dataKey="value" cornerRadius={12} background={{ fill: "var(--chart-grid)" }} fill={color} isAnimationActive={!reduce} animationDuration={1300} />
            </RadialBarChart>
          </ResponsiveContainer>
        )}
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <span className="font-mono text-sm font-bold tabular-nums" style={{ color }}>
            {display}
          </span>
        </div>
      </div>
      <span className="mt-1 text-[11px] font-medium uppercase tracking-wider text-text-secondary">{label}</span>
    </div>
  );
}

/** Row of animated arc gauges — day P&L, win rate, vs-benchmark, risk. */
export function PerformanceGauges() {
  return (
    <div>
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Performance</h3>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {gauges.map((g) => (
          <Gauge key={g.id} {...g} />
        ))}
      </div>
    </div>
  );
}
