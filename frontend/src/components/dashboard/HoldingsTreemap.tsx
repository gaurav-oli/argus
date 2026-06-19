"use client";

import { ResponsiveContainer, Treemap } from "recharts";
import { holdings } from "@/lib/mockData";
import { useMounted } from "@/lib/useMounted";

const bySymbol = new Map(holdings.map((h) => [h.symbol, h]));

type TileProps = {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  name?: string;
};

/** One treemap cell — colour intensity encodes today's move, label shown if it fits. */
function Tile({ x = 0, y = 0, width = 0, height = 0, name = "" }: TileProps) {
  if (width <= 0 || height <= 0) return null;
  const h = bySymbol.get(name);
  const change = h?.changePct ?? 0;
  const up = change >= 0;
  const mag = Math.min(Math.abs(change) / 6, 1);
  const base = up ? "0,255,136" : "255,59,92";
  const fill = change === 0 ? "rgba(107,114,128,0.18)" : `rgba(${base},${0.14 + mag * 0.5})`;
  const showText = width > 46 && height > 34;
  return (
    <g>
      <rect
        x={x + 1.5}
        y={y + 1.5}
        width={width - 3}
        height={height - 3}
        rx={8}
        fill={fill}
        className="stroke-transparent transition-[stroke] duration-200 hover:stroke-white/40"
        strokeWidth={1.5}
      />
      {showText && (
        <>
          <text x={x + 10} y={y + 22} className="fill-text-primary font-mono" fontSize={13} fontWeight={700}>
            {name}
          </text>
          <text x={x + 10} y={y + 38} fontSize={11} className="font-mono" fill={change === 0 ? "#6B7280" : up ? "#00FF88" : "#FF3B5C"}>
            {change >= 0 ? "+" : ""}
            {change.toFixed(1)}%
          </text>
        </>
      )}
    </g>
  );
}

/** Holdings heatmap — whole book at a glance, tiles sized by position value. */
export function HoldingsTreemap() {
  const mounted = useMounted();
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Holdings Heatmap</h3>
        <span className="text-[11px] text-text-secondary">size = value · colour = today</span>
      </div>
      <div className="mt-3 h-72 w-full">
        {mounted && (
          <ResponsiveContainer width="100%" height="100%">
            <Treemap data={holdings} dataKey="value" nameKey="symbol" content={<Tile />} isAnimationActive={false} stroke="none" />
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
