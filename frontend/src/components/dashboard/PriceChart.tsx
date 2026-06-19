"use client";

import { useEffect, useRef, useState } from "react";
import { AreaSeries, ColorType, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { fullPriceSeries } from "@/lib/mockData";

const RANGES = [
  { label: "1W", days: 7 },
  { label: "1M", days: 30 },
  { label: "3M", days: 90 },
  { label: "1Y", days: 365 },
] as const;

/**
 * Portfolio value over time — TradingView Lightweight Charts (canvas, the lib
 * the architecture picked for price charts). Range toggles reslice the series.
 */
export function PriceChart() {
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Area"> | null>(null);
  const [days, setDays] = useState(90);

  // create the chart once
  useEffect(() => {
    if (!wrapRef.current) return;
    const chart = createChart(wrapRef.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: "#6B7280",
        fontFamily: "var(--font-sans), sans-serif",
        attributionLogo: false,
      },
      grid: { vertLines: { visible: false }, horzLines: { color: "rgba(255,255,255,0.04)" } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, fixLeftEdge: true, fixRightEdge: true },
      crosshair: { mode: 0, vertLine: { color: "rgba(255,255,255,0.2)", labelVisible: false }, horzLine: { visible: false } },
      handleScale: false,
      handleScroll: false,
    });
    const series = chart.addSeries(AreaSeries, {
      lineColor: "#00D4FF",
      lineWidth: 2,
      topColor: "rgba(0,212,255,0.35)",
      bottomColor: "rgba(0,212,255,0.0)",
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
        <div className="flex gap-1 rounded-lg bg-white/[0.04] p-0.5">
          {RANGES.map((r) => (
            <button
              key={r.label}
              onClick={() => setDays(r.days)}
              className={`rounded-md px-2.5 py-1 text-[11px] font-medium transition-colors ${
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
