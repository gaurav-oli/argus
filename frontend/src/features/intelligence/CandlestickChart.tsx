"use client";

import { CandlestickSeries, ColorType, LineSeries, LineStyle, createChart, type Time } from "lightweight-charts";
import { useTheme } from "@/components/theme/ThemeProvider";
import type { ChartDetail } from "@/lib/apiClient";
import { useEffect, useRef } from "react";

/** lightweight-charts is canvas — read computed CSS vars rather than passing them in. */
function cssVar(name: string, fallback: string) {
  if (typeof window === "undefined") return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

/**
 * The actual candlestick chart Agent 10's study was computed from: daily candles, the 20/50/200-day averages,
 * and the nearest support and resistance as labelled price lines — so what the agent "sees" is what you see.
 */
export function CandlestickChart({ detail }: { detail: ChartDetail }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const { theme } = useTheme();

  useEffect(() => {
    if (!wrapRef.current) return;
    const gains = cssVar("--chart-gains", "#15a34a");
    const losses = cssVar("--chart-losses", "#dc2626");
    const accent = cssVar("--chart-accent", "#0891b2");
    const axis = cssVar("--chart-axis", "#6b7280");
    const grid = cssVar("--chart-grid", "rgba(128,128,128,0.12)");

    const chart = createChart(wrapRef.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: axis,
        fontFamily: "var(--font-sans), sans-serif",
        attributionLogo: false,
      },
      grid: { vertLines: { visible: false }, horzLines: { color: grid } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, fixLeftEdge: true, fixRightEdge: true },
      crosshair: { mode: 0 },
    });

    const candles = chart.addSeries(CandlestickSeries, {
      upColor: gains,
      downColor: losses,
      borderUpColor: gains,
      borderDownColor: losses,
      wickUpColor: gains,
      wickDownColor: losses,
      priceLineVisible: false,
    });
    candles.setData(
      detail.candles.map((c) => ({ time: c.time as Time, open: c.open, high: c.high, low: c.low, close: c.close })),
    );

    const line = (points: { time: string; value: number }[], color: string, width: 1 | 2) => {
      if (points.length === 0) return;
      const s = chart.addSeries(LineSeries, { color, lineWidth: width, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
      s.setData(points.map((p) => ({ time: p.time as Time, value: p.value })));
    };
    line(detail.sma20, accent, 1);
    line(detail.sma50, "#d97706", 1);
    line(detail.sma200, "#8b5cf6", 2);

    if (detail.support != null) {
      candles.createPriceLine({ price: detail.support, color: gains, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true, title: "support" });
    }
    if (detail.resistance != null) {
      candles.createPriceLine({ price: detail.resistance, color: losses, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true, title: "resistance" });
    }
    chart.timeScale().fitContent();
    return () => chart.remove();
  }, [detail, theme]);

  return (
    <div>
      <div className="h-72 w-full" ref={wrapRef} />
      <p className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-[10px] text-text-secondary">
        <span><span className="mr-1 inline-block h-0.5 w-3 bg-accent align-middle" />20-day</span>
        <span><span className="mr-1 inline-block h-0.5 w-3 align-middle" style={{ backgroundColor: "#d97706" }} />50-day</span>
        <span><span className="mr-1 inline-block h-0.5 w-3 align-middle" style={{ backgroundColor: "#8b5cf6" }} />200-day</span>
        <span>dashed: nearest support / resistance</span>
      </p>
    </div>
  );
}
