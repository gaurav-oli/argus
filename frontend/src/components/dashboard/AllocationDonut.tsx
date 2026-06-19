"use client";

import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";
import { allocation } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

/** Sector allocation donut with a legend; slices animate in radially. */
export function AllocationDonut() {
  const mounted = useMounted();
  return (
    <div className="flex h-full flex-col">
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Allocation</h3>
      <div className="mt-2 flex flex-1 items-center gap-4">
        <div className="relative h-32 w-32 shrink-0">
          {mounted && (
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={allocation}
                dataKey="value"
                nameKey="name"
                innerRadius="62%"
                outerRadius="100%"
                paddingAngle={3}
                cornerRadius={4}
                stroke="none"
                animationDuration={1200}
              >
                {allocation.map((slice) => (
                  <Cell key={slice.name} fill={slice.color} />
                ))}
              </Pie>
            </PieChart>
          </ResponsiveContainer>
          )}
          <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-[10px] uppercase tracking-wider text-text-secondary">Sectors</span>
            <span className="font-mono text-lg font-bold text-text-primary">{allocation.length}</span>
          </div>
        </div>

        <ul className="flex-1 space-y-1.5">
          {allocation.map((s) => (
            <li key={s.name} className="flex items-center gap-2 text-xs">
              <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: s.color }} />
              <span className="flex-1 text-text-secondary">{s.name}</span>
              <span className="font-mono font-medium text-text-primary tabular-nums">{s.value}%</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
