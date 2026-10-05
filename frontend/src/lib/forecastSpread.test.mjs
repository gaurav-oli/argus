// Unit tests for the forecast-spread math. Run with `npm test` (Node's built-in runner; Node 23+
// strips the TypeScript types when importing the .ts module directly — no extra dependencies).
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  MIN_CLOSES,
  blockFor,
  buildSpread,
  dailyVolatility,
  inverseNormal,
  pct,
  tradingDays,
} from "./forecastSpread.ts";

/** Standard normal CDF via a high-precision erf series, to check the inverse independently. */
function phi(z) {
  const t = 1 / (1 + 0.5 * Math.abs(z) / Math.SQRT2);
  const y =
    1 -
    t *
      Math.exp(
        -(z * z) / 2 -
          1.26551223 +
          t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))),
      );
  return z >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
}

describe("inverseNormal", () => {
  it("is zero at the median and antisymmetric", () => {
    assert.ok(Math.abs(inverseNormal(0.5)) < 1e-12);
    assert.ok(Math.abs(inverseNormal(0.2) + inverseNormal(0.8)) < 1e-9);
  });
  it("inverts the normal CDF across the body and both tails", () => {
    for (const p of [0.001, 0.01, 0.0242, 0.1, 0.3, 0.64, 0.9, 0.976, 0.999]) {
      assert.ok(Math.abs(phi(inverseNormal(p)) - p) < 1e-6, `p=${p}`);
    }
  });
  it("rejects probabilities outside (0, 1)", () => {
    assert.throws(() => inverseNormal(0), RangeError);
    assert.throws(() => inverseNormal(1), RangeError);
  });
});

describe("dailyVolatility", () => {
  it("returns null with too little history", () => {
    assert.equal(dailyVolatility(Array.from({ length: MIN_CLOSES - 1 }, (_, i) => 100 + i)), null);
  });
  it("returns null for a flat price (zero volatility)", () => {
    assert.equal(dailyVolatility(Array(40).fill(100)), null);
  });
  it("ignores non-positive and non-finite closes", () => {
    const clean = Array.from({ length: 30 }, (_, i) => 100 * (i % 2 ? 1.01 : 0.99));
    const dirty = [...clean.slice(0, 10), 0, NaN, -5, ...clean.slice(10)];
    assert.equal(dailyVolatility(dirty), dailyVolatility(clean));
  });
  it("matches the sample stdev of log returns", () => {
    const closes = [100, 102, 101, 103, 104, 102, 105, 107, 106, 108, 110, 109, 111, 112, 110, 113, 115, 114, 116, 118, 117, 119];
    const rets = closes.slice(1).map((c, i) => Math.log(c / closes[i]));
    const m = rets.reduce((s, r) => s + r, 0) / rets.length;
    const sd = Math.sqrt(rets.reduce((s, r) => s + (r - m) ** 2, 0) / (rets.length - 1));
    assert.ok(Math.abs(dailyVolatility(closes) - sd) < 1e-12);
  });
});

describe("buildSpread", () => {
  const base = { bullProbability: 0.64, direction: "BULLISH", holdDays: 30, sigmaDaily: 0.02 };

  it("puts exactly the model's odds above zero (adds no new probability)", () => {
    for (const p of [0.3, 0.5, 0.64, 0.71, 0.9]) {
      const s = buildSpread({ ...base, bullProbability: p });
      assert.ok(Math.abs(phi(s.median / s.sigma) - p) < 1e-6, `p=${p}`);
    }
  });

  it("scales sigma with the square root of trading days", () => {
    const s = buildSpread(base);
    assert.ok(Math.abs(s.sigma - 0.02 * Math.sqrt(tradingDays(30))) < 1e-12);
    assert.equal(tradingDays(30), 21);
    assert.equal(tradingDays(0), 1);
  });

  it("centres a column exactly on zero and lights the call's side", () => {
    const bull = buildSpread(base);
    const mid = (bull.columns.length - 1) / 2;
    assert.equal(bull.columns[mid].side, "zero");
    assert.equal(bull.columns[mid].x, 0);
    assert.ok(bull.columns.filter((c) => c.side === "up").every((c) => c.onBetSide));
    assert.ok(bull.columns.filter((c) => c.side === "down").every((c) => !c.onBetSide));

    const bear = buildSpread({ ...base, bullProbability: 0.3, direction: "BEARISH" });
    assert.ok(bear.columns.filter((c) => c.side === "down").every((c) => c.onBetSide));
    assert.equal(bear.betSidePercent, 70);
  });

  it("peaks at full height near the median", () => {
    const s = buildSpread(base);
    const max = Math.max(...s.columns.map((c) => c.level));
    assert.equal(max, s.rows * 8);
    const peak = s.columns.find((c) => c.level === max);
    assert.ok(Math.abs(peak.x - s.median) <= (2 * s.range) / (s.columns.length - 1));
  });

  it("places stop and target markers relative to the entry and widens the axis to fit them", () => {
    const s = buildSpread({ ...base, sigmaDaily: 0.005, entryPrice: 100, targetPrice: 140, stopPrice: 90 });
    const target = s.markers.find((m) => m.kind === "target");
    const stop = s.markers.find((m) => m.kind === "stop");
    assert.ok(Math.abs(target.ret - 0.4) < 1e-12);
    assert.ok(Math.abs(stop.ret + 0.1) < 1e-12);
    assert.ok(s.range >= 0.4);
    assert.ok(target.column > (s.columns.length - 1) / 2 && stop.column < (s.columns.length - 1) / 2);
  });

  it("caps the axis at ±60% and clamps extreme odds", () => {
    const s = buildSpread({ ...base, sigmaDaily: 0.2, bullProbability: 1 });
    assert.equal(s.range, 0.6);
    assert.ok(Number.isFinite(s.median));
    assert.equal(s.betSidePercent, 100);
  });

  it("omits price markers without an entry price", () => {
    const s = buildSpread({ ...base, targetPrice: 120, stopPrice: 90 });
    assert.deepEqual(s.markers.map((m) => m.kind), ["entry"]);
  });
});

describe("blockFor / pct", () => {
  it("fills rows bottom-up in eighth blocks", () => {
    assert.equal(blockFor(0, 0), " ");
    assert.equal(blockFor(4, 0), "▄");
    assert.equal(blockFor(8, 0), "█");
    assert.equal(blockFor(12, 0), "█");
    assert.equal(blockFor(12, 1), "▄");
    assert.equal(blockFor(12, 2), " ");
  });
  it("formats signed percentages with a true minus", () => {
    assert.equal(pct(0.042), "+4.2%");
    assert.equal(pct(-0.03), "−3.0%");
    assert.equal(pct(-0.6, 0), "−60%");
  });
});
