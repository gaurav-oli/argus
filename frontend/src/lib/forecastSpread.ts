/**
 * Forecast spread — turns a recommendation's model odds into a picture of the returns they imply.
 *
 * Argus's probabilities are model-derived (the scoring engine), never invented in the UI. This module
 * adds NO new probability: it takes the model's bull odds `p` for the call's own horizon, plus the
 * stock's realized volatility from its real daily candles, and finds the one normal distribution of
 * horizon returns that (a) has that volatility and (b) puts exactly `p` of its mass above zero. That
 * curve is an illustration of the model's number, not a second forecast — the UI labels it so.
 *
 *   σ_h = σ_daily · √(trading days in the horizon)
 *   μ   = σ_h · Φ⁻¹(p)          ⇒   P(return > 0) = Φ(μ / σ_h) = p
 */

/** Acklam's rational approximation of the inverse standard-normal CDF (|error| < 1.2e-9). */
export function inverseNormal(p: number): number {
  if (p <= 0 || p >= 1) throw new RangeError(`p must be in (0, 1), got ${p}`);
  const a = [-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239];
  const b = [-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572];
  const c = [-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783];
  const d = [0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416];
  const lo = 0.02425;
  if (p < lo) {
    const q = Math.sqrt(-2 * Math.log(p));
    return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  if (p > 1 - lo) {
    const q = Math.sqrt(-2 * Math.log(1 - p));
    return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  const q = p - 0.5;
  const r = q * q;
  return ((((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q) / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
}

/** Minimum closes needed for a volatility estimate worth drawing. */
export const MIN_CLOSES = 21;
/** How far back the volatility looks (trading days). */
export const VOL_LOOKBACK = 60;

/**
 * Daily volatility (sample standard deviation of log returns) over the last {@link VOL_LOOKBACK}
 * closes, or null when there are fewer than {@link MIN_CLOSES} usable (positive, finite) closes.
 * `closes` must be oldest → newest.
 */
export function dailyVolatility(closes: number[]): number | null {
  const usable = closes.filter((c) => Number.isFinite(c) && c > 0).slice(-(VOL_LOOKBACK + 1));
  if (usable.length < MIN_CLOSES) return null;
  const rets: number[] = [];
  for (let i = 1; i < usable.length; i++) rets.push(Math.log(usable[i] / usable[i - 1]));
  const mean = rets.reduce((s, r) => s + r, 0) / rets.length;
  const variance = rets.reduce((s, r) => s + (r - mean) ** 2, 0) / (rets.length - 1);
  const sd = Math.sqrt(variance);
  return sd > 0 ? sd : null;
}

/** Calendar days → trading days (252 per 365), at least 1. */
export function tradingDays(calendarDays: number): number {
  return Math.max(1, Math.round((calendarDays * 252) / 365));
}

export interface SpreadInput {
  /** Model bull odds, 0–1 (P(return over the horizon > 0)). */
  bullProbability: number;
  /** Which side the call bets on. */
  direction: "BULLISH" | "BEARISH";
  /** The call's own horizon in calendar days. */
  holdDays: number;
  /** Realized daily volatility (see {@link dailyVolatility}). */
  sigmaDaily: number;
  /** Optional price levels, drawn as markers relative to the entry. */
  entryPrice?: number | null;
  targetPrice?: number | null;
  stopPrice?: number | null;
  /** Histogram columns (odd, so one column sits exactly on 0%). */
  columns?: number;
  /** Vertical resolution: block-character rows × 8 levels each. */
  rows?: number;
}

export interface SpreadColumn {
  /** Return at the column centre, as a fraction (0.05 = +5%). */
  x: number;
  /** Bar height in eighth-blocks, 0 … rows·8. */
  level: number;
  /** True when this column is on the side the call bets on. */
  onBetSide: boolean;
  /** Up (gain) side of zero; the exact-zero column counts as neither. */
  side: "up" | "down" | "zero";
}

export interface SpreadMarker {
  kind: "stop" | "entry" | "target";
  column: number;
  /** Return vs the entry price, as a fraction. */
  ret: number;
}

export interface Spread {
  columns: SpreadColumn[];
  markers: SpreadMarker[];
  /** Axis half-width, as a fraction (axis runs −range … +range). */
  range: number;
  /** One-sigma move over the horizon, as a fraction. */
  sigma: number;
  /** The centre (median) return the odds imply, as a fraction. */
  median: number;
  /** The model's probability on the call's side, 0–100 (bull% for bullish, bear% for bearish). */
  betSidePercent: number;
  rows: number;
}

/** Odds are clamped away from 0/1 so Φ⁻¹ stays finite; the displayed % is the model's own number. */
const P_FLOOR = 0.01;
/** Never draw an axis wider than ±60% — beyond that the picture stops being informative. */
const MAX_RANGE = 0.6;

export function buildSpread(input: SpreadInput): Spread {
  const n = input.columns ?? 41;
  const rows = input.rows ?? 4;
  const maxLevel = rows * 8;
  const p = Math.min(1 - P_FLOOR, Math.max(P_FLOOR, input.bullProbability));
  const sigma = input.sigmaDaily * Math.sqrt(tradingDays(input.holdDays));
  const median = sigma * inverseNormal(p);

  const rel = (price: number | null | undefined) =>
    price != null && input.entryPrice != null && input.entryPrice > 0 ? price / input.entryPrice - 1 : null;
  const targetRet = rel(input.targetPrice);
  const stopRet = rel(input.stopPrice);

  const reach = Math.max(Math.abs(median) + 3 * sigma, Math.abs(targetRet ?? 0), Math.abs(stopRet ?? 0));
  const range = Math.min(MAX_RANGE, reach * 1.08);

  const half = (n - 1) / 2;
  const xs = Array.from({ length: n }, (_, i) => ((i - half) / half) * range);
  const density = xs.map((x) => Math.exp(-((x - median) ** 2) / (2 * sigma * sigma)));
  const peak = Math.max(...density);
  const bull = input.direction === "BULLISH";

  const columns: SpreadColumn[] = xs.map((x, i) => {
    const side: SpreadColumn["side"] = i === half ? "zero" : x > 0 ? "up" : "down";
    return {
      x,
      level: Math.round((density[i] / peak) * maxLevel),
      side,
      onBetSide: side === "zero" ? false : bull ? side === "up" : side === "down",
    };
  });

  const toColumn = (ret: number) => Math.max(0, Math.min(n - 1, Math.round(half + (ret / range) * half)));
  const markers: SpreadMarker[] = [{ kind: "entry", column: half, ret: 0 }];
  if (stopRet != null) markers.push({ kind: "stop", column: toColumn(stopRet), ret: stopRet });
  if (targetRet != null) markers.push({ kind: "target", column: toColumn(targetRet), ret: targetRet });

  const bullPct = Math.round(input.bullProbability * 100);
  return { columns, markers, range, sigma, median, betSidePercent: bull ? bullPct : 100 - bullPct, rows };
}

const BLOCKS = " ▁▂▃▄▅▆▇█";

/**
 * The block character for one cell of a column `level` eighth-blocks tall, on text row `row`
 * (0 = bottom row). Each row holds 8 levels.
 */
export function blockFor(level: number, row: number): string {
  const inRow = Math.max(0, Math.min(8, level - row * 8));
  return BLOCKS[inRow];
}

/** "+4.2%" / "−3.0%" with a true minus sign. */
export function pct(fraction: number, digits = 1): string {
  const v = (fraction * 100).toFixed(digits);
  return fraction >= 0 ? `+${v}%` : `−${v.replace("-", "")}%`;
}
