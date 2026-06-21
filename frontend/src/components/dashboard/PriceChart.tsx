"use client";

import { useEffect, useRef, useState } from "react";
import { AreaSeries, ColorType, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { useTheme } from "@/components/theme/ThemeProvider";
import { fullPriceSeries } from "@/lib/mockData";

const RANGES = [
  { label: "1W", days: 7 },
  { label: "1M", days: 30 },
  { label: "3M", days: 90 },
  { label: "1Y", days: 365 },
] as const;

/** Resolve a CSS variable to its computed value (lightweight-charts is canvas,
 *  so it can't consume CSS vars directly — read them once per theme). */
function cssVar(name: string, fallback: string) {
  if (typeof window === "undefined") return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

/** Convert "#rrggbb" + alpha → rgba(); used for the area gradient fill. */
function withAlpha(hex: string, alpha: number) {
  const m = hex.replace("#", "");
  if (m.length !== 6) return hex;
  const r = parseInt(m.slice(0, 2), 16);
  const g = parseInt(m.slice(2, 4), 16);
  const b = parseInt(m.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${alpha})`;
}

/**
 * Portfolio value over time — TradingView Lightweight Charts (canvas; the lib
 * the architecture picked for price charts). Range toggles reslice the series;
 * colors follow the active theme (re-applied on toggle).
 */
export function PriceChart() {
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Area"> | null>(null);
  const [days, setDays] = useState(90);
  const { theme } = useTheme();

  // create the chart once
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
    const series = chart.addSeries(AreaSeries, {
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: false,
    });
    chartRef.current = chart;
    seriesRef.current = series;
    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  // (re)apply theme colors — runs on mount and whenever the theme flips
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

  // update data on range change
  useEffect(() => {
    if (!seriesRef.current || !chartRef.current) return;
    seriesRef.current.setData(fullPriceSeries.slice(-days));
    chartRef.current.timeScale().fitContent();
  }, [days]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Portfolio Value</h3>
        <div className="flex gap-1 rounded-lg bg-[var(--hover-wash)] p-0.5">
          {RANGES.map((r) => (
            <button
              key={r.label}
              onClick={() => setDays(r.days)}
              className={`cursor-pointer rounded-md px-2.5 py-1 text-[11px] font-medium transition-colors ${
                days === r.days ? "bg-accent/20 text-accent" : "text-text-secondary hover:text-text-primary"
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>
      <div ref={wrapRef} className="mt-3 h-72 w-full" />
    </div>
  );
}
