"""Strategy rebuild, Phase 3 — choose the "stretched" cutoff (docs/strategy-rebuild-plan.md).

Stretch = (close - 20-day average) / ATR14, i.e. how many normal days' moves the price sits above its average.
Only three cutoffs are tried (2, 3, 4), fitted on data before 2023 and checked on 2023 onward.

"Would-be BUY" proxy (the 10-year universe has no Agent 5 calls): a stock in an uptrend (close > SMA50 > SMA200),
sampled every 5th trading day to limit overlap. Under the rule, a stretched call becomes a pullback order: it fills
only if the price comes back to the 20-day average + 0.5 ATR within 10 trading days, then is held 20 trading days.
Unstretched calls are taken at once and held 20 trading days. Results are 20-day returns minus SPY's over the same
days, after a 0.3% round-trip cost, pooled into independent week x sector clusters.

Pre-registered pass test (hold-out, >= 30 clusters): the rule improves the average 20-day excess return of BUY calls
by >= 1.0 point, and the expired (unfilled) calls don't outperform the filled ones.

Caveat: the universe only holds companies that still exist (survivorship). That matters less when comparing two
rules on the same stocks; re-run once Phase 5a's list of dead companies exists.

Usage: python -I phase3_stretch_backtest.py OHLC_CSV SECTORS_CSV [CALLS_CSV]
"""
import sys

import numpy as np
import pandas as pd

H = 20
EXPIRY = 10
ZONE_ATR = 0.5
COST = 0.3
CUTOFFS = [2.0, 3.0, 4.0]
SPLIT = pd.Timestamp("2023-01-01")
MIN_CLUSTERS = 30
MARGIN = 1.0


def load(path):
    df = pd.read_csv(path, parse_dates=["candle_date"])
    piv = {k: df.pivot(index="candle_date", columns="ticker", values=k).sort_index() for k in ["open", "high", "low", "close"]}
    return piv


def pooled(values, keys):
    """Mean over independent clusters and the cluster count."""
    s = pd.Series(values).groupby(pd.Series(keys)).mean()
    return (s.mean() if len(s) else np.nan), len(s)


def main():
    px = load(sys.argv[1])
    sectors = pd.read_csv(sys.argv[2], header=None, names=["ticker", "sector"]).set_index("ticker")["sector"].to_dict()
    o, h, l, c = px["open"], px["high"], px["low"], px["close"]
    spy = c["SPY"]
    keep = [t for t in c.columns if t in sectors]
    o, h, l, c = o[keep], h[keep], l[keep], c[keep]
    prev = c.shift(1)
    tr = pd.concat([(h - l), (h - prev).abs(), (l - prev).abs()]).groupby(level=0).max()
    atr = tr.rolling(14).mean()
    sma20, sma50, sma200 = c.rolling(20).mean(), c.rolling(50).mean(), c.rolling(200).mean()
    stretch = (c - sma20) / atr
    uptrend = (c > sma50) & (sma50 > sma200)
    idx = c.index
    spy = spy.reindex(idx)

    rows = []
    for i in range(200, len(idx) - (EXPIRY + H + 1), 5):
        d = idx[i]
        live = uptrend.iloc[i]
        for t in live[live].index:
            st = stretch.iat[i, c.columns.get_loc(t)]
            if np.isnan(st):
                continue
            col = c.columns.get_loc(t)
            p0, s0 = c.iat[i, col], spy.iat[i]
            p20, s20 = c.iat[i + H, col], spy.iat[i + H]
            if np.isnan(p20) or np.isnan(s20) or np.isnan(s0):
                continue
            immediate = 100 * ((p20 / p0 - 1) - (s20 / s0 - 1)) - COST
            # pullback order: fill at min(open, zone) on the first day the low reaches the zone
            zone = sma20.iat[i, col] + ZONE_ATR * atr.iat[i, col]
            fill = None
            for k in range(1, EXPIRY + 1):
                if l.iat[i + k, col] <= zone:
                    fill = (i + k, min(o.iat[i + k, col], zone))
                    break
            pull = None
            if fill:
                j, fp = fill
                pj, sj, sjh = c.iat[j + H, col], spy.iat[j], spy.iat[j + H]
                if not (np.isnan(pj) or np.isnan(sj) or np.isnan(sjh)):
                    pull = 100 * ((pj / fp - 1) - (sjh / sj - 1)) - COST
            week = d.to_period("W").start_time
            rows.append((d, t, sectors[t], st, immediate, pull, f"{week.date()}|{sectors[t]}"))
    df = pd.DataFrame(rows, columns=["date", "ticker", "sector", "stretch", "immediate", "pullback", "cluster"])
    print(f"would-be BUY calls: {len(df):,} ({df.date.min().date()} .. {df.date.max().date()})")

    def evaluate(part, k):
        base, nb = pooled(part.immediate.values, part.cluster.values)
        stretched = part.stretch >= k
        traded = part[~stretched].immediate.tolist() + part[stretched & part.pullback.notna()].pullback.tolist()
        tkeys = part[~stretched].cluster.tolist() + part[stretched & part.pullback.notna()].cluster.tolist()
        rule, nr = pooled(traded, tkeys)
        filled = part[stretched & part.pullback.notna()]
        expired = part[stretched & part.pullback.isna()]
        f_mean, nf = pooled(filled.pullback.values, filled.cluster.values)
        e_mean, ne = pooled(expired.immediate.values, expired.cluster.values)
        return dict(k=k, base=base, rule=rule, gain=rule - base, clusters=nr, stretched=int(stretched.sum()),
                    filled=len(filled), expired=len(expired), filled_mean=f_mean, expired_mean=e_mean,
                    filled_clusters=nf, expired_clusters=ne)

    fit, hold = df[df.date < SPLIT], df[df.date >= SPLIT]
    print("\nFIT (before 2023):")
    fits = [evaluate(fit, k) for k in CUTOFFS]
    for r in fits:
        print(f"  k={r['k']:.0f}: no-rule {r['base']:+.2f}  rule {r['rule']:+.2f}  gain {r['gain']:+.2f} pts"
              f"  | stretched {r['stretched']:,}: filled {r['filled']:,} ({r['filled_mean']:+.2f})"
              f" expired {r['expired']:,} (would have made {r['expired_mean']:+.2f})")
    best = max(fits, key=lambda r: r["gain"])
    print(f"\nchosen on FIT: k={best['k']:.0f}")
    r = evaluate(hold, best["k"])
    print(f"HOLD-OUT (2023+): no-rule {r['base']:+.2f}  rule {r['rule']:+.2f}  gain {r['gain']:+.2f} pts on {r['clusters']} clusters"
          f" | filled {r['filled']:,} ({r['filled_mean']:+.2f}) expired {r['expired']:,} (would have made {r['expired_mean']:+.2f})")
    passed = r["clusters"] >= MIN_CLUSTERS and r["gain"] >= MARGIN and not (r["expired_mean"] > r["filled_mean"])
    print(f"PASS TEST: {'PASS' if passed else 'FAIL'} (needs gain >= {MARGIN} on >= {MIN_CLUSTERS} clusters,"
          f" expired not beating filled)")

    # The actual Agent 5 BUY calls since 2026-09-25, if supplied
    if len(sys.argv) > 3:
        calls = pd.read_csv(sys.argv[3], parse_dates=["call_date"])
        out = []
        for _, row in calls.iterrows():
            t = row.ticker
            if t not in c.columns:
                continue
            pos = idx.searchsorted(row.call_date)
            if pos >= len(idx):
                continue
            st = stretch.iat[pos, c.columns.get_loc(t)]
            out.append((st, row.excess_20))
        cdf = pd.DataFrame(out, columns=["stretch", "excess_20"]).dropna()
        print(f"\nAgent 5 BUY calls since 2026-09-25 with a 20-day result: {len(cdf)}")
        for k in CUTOFFS:
            s = cdf[cdf.stretch >= k]
            n = cdf[cdf.stretch < k]
            print(f"  k={k:.0f}: stretched {len(s)} avg {s.excess_20.mean():+.2f} | not {len(n)} avg {n.excess_20.mean():+.2f}")
    return best["k"], passed, r


if __name__ == "__main__":
    main()


def mirror(ohlc_csv, sectors_csv):
    """Decision D (mirror rule): would-be AVOID calls on stocks in a downtrend (close < SMA50 < SMA200). The rule turns an
    AVOID on a stock stretched >= k ATR *below* its 20-day average into WATCH. It helps if those stretched-down stocks
    go on to beat SPY (the AVOID would have been wrong). Same fit/hold-out split, cutoffs and pass bar."""
    px = load(ohlc_csv)
    sectors = pd.read_csv(sectors_csv, header=None, names=["ticker", "sector"]).set_index("ticker")["sector"].to_dict()
    h, l, c = px["high"], px["low"], px["close"]
    spy = c["SPY"]
    keep = [t for t in c.columns if t in sectors]
    h, l, c = h[keep], l[keep], c[keep]
    prev = c.shift(1)
    tr = pd.concat([(h - l), (h - prev).abs(), (l - prev).abs()]).groupby(level=0).max()
    atr = tr.rolling(14).mean()
    sma20, sma50, sma200 = c.rolling(20).mean(), c.rolling(50).mean(), c.rolling(200).mean()
    stretch = (c - sma20) / atr
    down = (c < sma50) & (sma50 < sma200)
    fwd = 100 * ((c.shift(-H) / c - 1).sub(spy.shift(-H) / spy - 1, axis=0))
    rows = []
    for i in range(200, len(c.index) - H - 1, 5):
        d = c.index[i]
        mask = down.iloc[i]
        for t in mask[mask].index:
            st, ex = stretch.iat[i, c.columns.get_loc(t)], fwd.iat[i, c.columns.get_loc(t)]
            if np.isnan(st) or np.isnan(ex):
                continue
            # an AVOID call earns -excess (it wins when the stock lags SPY), less costs
            rows.append((d, sectors[t], st, -ex - COST, f"{d.to_period('W').start_time.date()}|{sectors[t]}"))
    df = pd.DataFrame(rows, columns=["date", "sector", "stretch", "avoid_edge", "cluster"])

    def ev(part, k):
        base, nb = pooled(part.avoid_edge.values, part.cluster.values)
        kept = part[part.stretch > -k]
        rule, nr = pooled(kept.avoid_edge.values, kept.cluster.values)
        dropped = part[part.stretch <= -k]
        dm, nd = pooled(dropped.avoid_edge.values, dropped.cluster.values)
        return dict(k=k, base=base, rule=rule, gain=rule - base, clusters=nr, dropped=len(dropped), dropped_edge=dm, dropped_clusters=nd)

    fit, hold = df[df.date < SPLIT], df[df.date >= SPLIT]
    print(f"\nMIRROR RULE — would-be AVOID calls: {len(df):,}")
    fits = [ev(fit, k) for k in CUTOFFS]
    for r in fits:
        print(f"  FIT k={r['k']:.0f}: no-rule {r['base']:+.2f}  rule {r['rule']:+.2f}  gain {r['gain']:+.2f}"
              f" | {r['dropped']:,} AVOIDs turned to WATCH (they averaged {r['dropped_edge']:+.2f})")
    best = max(fits, key=lambda r: r["gain"])
    r = ev(hold, best["k"])
    passed = r["clusters"] >= MIN_CLUSTERS and r["gain"] >= MARGIN
    print(f"  chosen k={best['k']:.0f}; HOLD-OUT: no-rule {r['base']:+.2f} rule {r['rule']:+.2f} gain {r['gain']:+.2f}"
          f" on {r['clusters']} clusters; dropped AVOIDs averaged {r['dropped_edge']:+.2f} on {r['dropped_clusters']} clusters"
          f" -> {'PASS' if passed else 'FAIL'}")
    return best["k"], passed


if __name__ == "__main__" and len(sys.argv) > 1 and "--mirror" in sys.argv:
    mirror(sys.argv[1], sys.argv[2])
