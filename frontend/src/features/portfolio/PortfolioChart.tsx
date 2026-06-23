"use client";

import { AreaSeries, ColorType, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { useTheme } from "@/components/theme/ThemeProvider";
import { getValueHistory } from "@/lib/apiClient";
import { useEffect, useRef, useState } from "react";

const RANGES = ["1D", "1W", "1M", "3M", "YTD", "1Y", "All"] as const;

/** lightweight-charts is canvas — read computed CSS vars rather than passing them in. */
function cssVar(name: string, fallback: string) {
  if (typeof window === "undefined") return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

function withAlpha(hex: string, alpha: number) {
  const m = hex.replace("#", "");
  if (m.length !== 6) return hex;
  return `rgba(${parseInt(m.slice(0, 2), 16)},${parseInt(m.slice(2, 4), 16)},${parseInt(m.slice(4, 6), 16)},${alpha})`;
}

/**
 * Real portfolio value over time (Story 3.6, FR-4) — TradingView Lightweight Charts (v5), the same
 * idiom as the dashboard `PriceChart` prototype but fed the persisted value history. Range toggles
 * refetch the series; shows an empty state until history accrues.
 */
export function PortfolioChart() {
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Area"> | null>(null);
  const [range, setRange] = useState<string>("1M");
  const [empty, setEmpty] = useState(false);
  const { theme } = useTheme();

  useEffect(() => {
    if (!wrapRef.current) return;
    const chart = createChart(wrapRef.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        fontFamily: "var(--font-sans), sans-serif",
        attributionLogo: false,
      },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, fixLeftEdge: true, fixRightEdge: true },
      crosshair: { mode: 0, horzLine: { visible: false } },
      handleScale: false,
      handleScroll: false,
    });
    const series = chart.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    chartRef.current = chart;
    seriesRef.current = series;
    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!chartRef.current || !seriesRef.current) return;
    const accent = cssVar("--chart-accent", "#00d4ff");
    const axis = cssVar("--chart-axis", "#6b7280");
    const grid = cssVar("--chart-grid", "rgba(255,255,255,0.04)");
    chartRef.current.applyOptions({
      layout: { textColor: axis },
      grid: { vertLines: { visible: false }, horzLines: { color: grid } },
      crosshair: { vertLine: { color: axis, labelVisible: false } },
    });
    seriesRef.current.applyOptions({
      lineColor: accent,
      topColor: withAlpha(accent, 0.35),
      bottomColor: withAlpha(accent, 0),
    });
  }, [theme]);

  useEffect(() => {
    let active = true;
    getValueHistory(range)
      .then((points) => {
        if (!active || !seriesRef.current || !chartRef.current) return;
        // A single point renders a degenerate line (e.g. the 1D range early on) — treat <2 as empty.
        setEmpty(points.length < 2);
        seriesRef.current.setData(points.map((p) => ({ time: p.date, value: p.totalValueCad })));
        chartRef.current.timeScale().fitContent();
      })
      .catch(() => active && setEmpty(true));
    return () => {
      active = false;
    };
  }, [range]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Portfolio value</h3>
        <div className="flex gap-1 rounded-lg bg-[var(--hover-wash)] p-0.5">
          {RANGES.map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              className={`cursor-pointer rounded-md px-2 py-1 text-[11px] font-medium transition-colors ${
                range === r ? "bg-accent/20 text-accent" : "text-text-secondary hover:text-text-primary"
              }`}
            >
              {r}
            </button>
          ))}
        </div>
      </div>
      <div className="relative mt-3 h-72 w-full">
        <div ref={wrapRef} className="h-full w-full" />
        {empty && (
          <div className="absolute inset-0 flex items-center justify-center text-sm text-text-secondary">
            No value history yet — it builds up daily.
          </div>
        )}
      </div>
    </div>
  );
}
