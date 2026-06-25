"use client";

import { useEffect, useState } from "react";
import { ResponsiveContainer, Treemap } from "recharts";
import { getPortfolioValue, type PortfolioSnapshot } from "@/lib/apiClient";
import { useMounted } from "@/lib/useMounted";

type TileProps = { x?: number; y?: number; width?: number; height?: number; name?: string };

/** Holdings heatmap (real positions, /api/portfolio/value) — tiles sized by value, coloured by move. */
export function HoldingsTreemap() {
  const mounted = useMounted();
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);

  useEffect(() => {
    let alive = true;
    getPortfolioValue().then((s) => alive && setSnap(s)).catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  const data = (snap?.positions ?? [])
    .map((p) => ({
      symbol: p.ticker,
      value: p.cadMarketValue ?? p.marketValue ?? 0,
      changePct: p.dayPnlPercent ?? p.totalPnlPercent ?? 0,
    }))
    .filter((d) => d.value > 0);
  const changeBySymbol = new Map(data.map((d) => [d.symbol, d.changePct]));

  // Render callback (not a component) closing over changeBySymbol, so each cell colours by today's
  // move without re-declaring a component in render.
  const renderTile = ({ x = 0, y = 0, width = 0, height = 0, name = "" }: TileProps) => {
    if (width <= 0 || height <= 0) return <g />;
    const change = changeBySymbol.get(name) ?? 0;
    const up = change >= 0;
    const mag = Math.min(Math.abs(change) / 6, 1);
    const baseVar = up ? "var(--chart-gains)" : "var(--chart-losses)";
    const pctMix = Math.round((0.14 + mag * 0.5) * 100);
    const fill =
      change === 0
        ? "color-mix(in srgb, var(--chart-axis) 18%, transparent)"
        : `color-mix(in srgb, ${baseVar} ${pctMix}%, transparent)`;
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
          className="stroke-transparent transition-[stroke] duration-200 hover:stroke-text-primary/40"
          strokeWidth={1.5}
        />
        {showText && (
          <>
            <text x={x + 10} y={y + 22} className="fill-text-primary font-mono" fontSize={13} fontWeight={700}>
              {name}
            </text>
            <text
              x={x + 10}
              y={y + 38}
              fontSize={11}
              className="font-mono"
              fill={change === 0 ? "var(--chart-axis)" : up ? "var(--chart-gains)" : "var(--chart-losses)"}
            >
              {change >= 0 ? "+" : ""}
              {change.toFixed(1)}%
            </text>
          </>
        )}
      </g>
    );
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Holdings Heatmap</h3>
        <span className="text-[11px] text-text-secondary">size = value · colour = today</span>
      </div>
      <div className="mt-3 h-72 w-full">
        {data.length === 0 ? (
          <div className="flex h-full items-center justify-center text-center text-xs text-text-secondary">
            {snap === null ? "Loading…" : "No holdings yet — import a statement to see the heatmap."}
          </div>
        ) : (
          mounted && (
            <ResponsiveContainer width="100%" height="100%">
              <Treemap data={data} dataKey="value" nameKey="symbol" content={renderTile} isAnimationActive={false} stroke="none" />
            </ResponsiveContainer>
          )
        )}
      </div>
    </div>
  );
}
