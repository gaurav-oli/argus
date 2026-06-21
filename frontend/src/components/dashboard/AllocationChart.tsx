"use client";

import { useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";
import { allocation } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

export function AllocationChart() {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const [active, setActive] = useState(0);
  const focus = allocation[active];

  return (
    <div className="flex h-full flex-col">
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Allocation</h3>

      <div className="mt-2 flex flex-1 items-center gap-3">
        <div className="relative h-32 w-32 shrink-0">
          {mounted && (
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={allocation}
                  dataKey="value"
                  nameKey="name"
                  innerRadius="58%"
                  outerRadius="88%"
                  paddingAngle={3}
                  cornerRadius={4}
                  stroke="none"
                  isAnimationActive={!reduce}
                  animationDuration={900}
                >
                  {allocation.map((slice, i) => (
                    <Cell
                      key={slice.name}
                      fill={slice.color}
                      stroke={i === active ? "var(--color-text-primary)" : "none"}
                      strokeWidth={i === active ? 1.5 : 0}
                      fillOpacity={i === active ? 1 : 0.4}
                      style={{ cursor: "pointer", transition: "fill-opacity 0.25s" }}
                      onMouseEnter={() => setActive(i)}
                    />
                  ))}
                </Pie>
              </PieChart>
            </ResponsiveContainer>
          )}
          {/* morphing center label */}
          <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <motion.span
              key={focus.name}
              initial={reduce ? false : { opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2 }}
              className="font-mono text-xl font-bold tabular-nums"
              style={{ color: focus.color }}
            >
              {focus.value}%
            </motion.span>
            <span className="max-w-[4.5rem] truncate text-[9px] uppercase tracking-wider text-text-secondary">{focus.name}</span>
          </div>
        </div>

        <ul className="flex-1 space-y-2">
          {allocation.map((s, i) => {
            const isActive = i === active;
            return (
              <li
                key={s.name}
                onMouseEnter={() => setActive(i)}
                className="cursor-default"
              >
                <div className="flex items-center justify-between text-xs">
                  <span className={`flex items-center gap-2 transition-colors ${isActive ? "text-text-primary" : "text-text-secondary"}`}>
                    <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: s.color }} />
                    {s.name}
                  </span>
                  <span className="font-mono font-medium text-text-primary tabular-nums">{s.value}%</span>
                </div>
                <div className="mt-1 h-1 overflow-hidden rounded-full bg-[var(--hairline)]">
                  <motion.div
                    initial={reduce ? false : { width: 0 }}
                    animate={{ width: `${s.value}%` }}
                    transition={reduce ? { duration: 0 } : { delay: 0.1 + i * 0.06, duration: 0.6, ease: "easeOut" }}
                    className="h-full rounded-full"
                    style={{ backgroundColor: s.color, opacity: isActive ? 1 : 0.55 }}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
