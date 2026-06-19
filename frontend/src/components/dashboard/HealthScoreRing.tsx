"use client";

import { PolarAngleAxis, RadialBar, RadialBarChart, ResponsiveContainer } from "recharts";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { healthScore } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

/**
 * Portfolio "health score" as an animated radial gauge with the score
 * counting up in the centre. Colour shifts green→cyan→amber by band.
 */
export function HealthScoreRing() {
  const mounted = useMounted();
  const { score, label, delta } = healthScore;
  const color = score >= 75 ? "#00FF88" : score >= 50 ? "#00D4FF" : "#FFB800";
  const data = [{ name: "score", value: score, fill: color }];

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Health Score</h3>
        <span className="text-[11px] font-medium text-gains">▲ {delta} this wk</span>
      </div>

      <div className="relative mt-2 flex-1">
        {mounted && (
        <ResponsiveContainer width="100%" height="100%" minHeight={150}>
          <RadialBarChart
            innerRadius="74%"
            outerRadius="100%"
            data={data}
            startAngle={220}
            endAngle={-40}
          >
            <defs>
              <linearGradient id="healthGrad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity={0.6} />
                <stop offset="100%" stopColor={color} stopOpacity={1} />
              </linearGradient>
            </defs>
            <PolarAngleAxis type="number" domain={[0, 100]} tick={false} />
            <RadialBar
              dataKey="value"
              cornerRadius={20}
              background={{ fill: "rgba(255,255,255,0.05)" }}
              fill="url(#healthGrad)"
              animationDuration={1400}
              animationEasing="ease-out"
            />
          </RadialBarChart>
        </ResponsiveContainer>
        )}

        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <AnimatedNumber value={score} className="font-mono text-4xl font-bold text-text-primary tabular-nums" />
          <span className="mt-0.5 text-xs font-medium" style={{ color }}>
            {label}
          </span>
        </div>
      </div>
    </div>
  );
}
