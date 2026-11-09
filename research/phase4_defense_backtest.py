"""Strategy rebuild, Phase 4 — defense in bad markets (docs/strategy-rebuild-plan.md).

Two levels, decided on the previous day's close (no look-ahead) and switched only after 3 days in a row:
  CAUTION = SPY below its 200-day average OR VIX above the cutoff  -> trading exposure x0.75
  DEFENSE = both                                                  -> trading exposure x0.50
VIX cutoffs tried: 20, 25, 30 only. The trading bucket is proxied by SPY exposure (cash earns nothing, conservative).

Pre-registered pass test: max drawdown cut by >= 20% (relative) while giving up <= 25% of total return, over at least
3 separate defense episodes. Also reports time to recover and the macro-event short (FOMC / jobs Friday).

Usage: python -I phase4_defense_backtest.py SPY_OHLC_CSV VIX_CSV
"""
import sys

import numpy as np
import pandas as pd

CUTOFFS = [20, 25, 30]
CONFIRM = 3
EXPOSURE = {0: 1.0, 1: 0.75, 2: 0.5}
SPLIT = pd.Timestamp("2023-01-01")


def drawdown_stats(equity):
    peak = equity.cummax()
    dd = equity / peak - 1
    max_dd = dd.min()
    # longest time under a previous peak, in trading days
    under, longest = 0, 0
    for v in dd.values:
        under = under + 1 if v < 0 else 0
        longest = max(longest, under)
    return max_dd, longest


def levels(spy, vix, cutoff):
    sma200 = spy.rolling(200).mean()
    raw = ((spy < sma200).astype(int) + (vix > cutoff).astype(int)).shift(1)  # yesterday's close decides today
    out, current, streak, last = [], 0, 0, None
    for v in raw.values:
        if np.isnan(v):
            out.append(0)
            continue
        v = int(v)
        streak = streak + 1 if v == last else 1
        last = v
        if streak >= CONFIRM:
            current = v
        out.append(current)
    return pd.Series(out, index=spy.index)


def episodes(lv):
    on = (lv > 0).astype(int)
    return int(((on.diff() == 1)).sum())


def run(spy, vix, cutoff, start=None, end=None):
    lv = levels(spy, vix, cutoff)
    ret = spy.pct_change().fillna(0)
    sl = slice(start, end)
    r, l = ret[sl], lv[sl]
    base = (1 + r).cumprod()
    exp = l.map(EXPOSURE)
    dfn = (1 + r * exp).cumprod()
    b_dd, b_rec = drawdown_stats(base)
    d_dd, d_rec = drawdown_stats(dfn)
    b_tot, d_tot = base.iloc[-1] - 1, dfn.iloc[-1] - 1
    return dict(cutoff=cutoff, base_ret=b_tot, def_ret=d_tot, base_dd=b_dd, def_dd=d_dd, base_rec=b_rec, def_rec=d_rec,
                dd_cut=1 - d_dd / b_dd, give_up=(b_tot - d_tot) / b_tot if b_tot > 0 else np.nan,
                episodes=episodes(l), days_defense=int((l == 2).sum()), days_caution=int((l == 1).sum()))


def show(tag, r):
    print(f"  {tag} VIX>{r['cutoff']}: return {r['base_ret']:+.0%} -> {r['def_ret']:+.0%} (gave up {r['give_up']:.0%}),"
          f" max drop {r['base_dd']:.1%} -> {r['def_dd']:.1%} (cut {r['dd_cut']:.0%}),"
          f" longest underwater {r['base_rec']} -> {r['def_rec']} days, episodes {r['episodes']},"
          f" caution {r['days_caution']}d / defense {r['days_defense']}d")


def passes(r):
    return r["dd_cut"] >= 0.20 and r["give_up"] <= 0.25 and r["episodes"] >= 3


def main():
    spy = pd.read_csv(sys.argv[1], header=None, names=["t", "d", "o", "h", "l", "c"], parse_dates=["d"])
    spy = spy[spy.t == "SPY"].set_index("d")["c"].astype(float).sort_index()
    vix = pd.read_csv(sys.argv[2], parse_dates=["date"]).set_index("date")["vix"].reindex(spy.index).ffill()

    print("FIT (before 2023):")
    fits = [run(spy, vix, k, end=SPLIT - pd.Timedelta(days=1)) for k in CUTOFFS]
    for r in fits:
        show("fit ", r)
    ok = [r for r in fits if r["give_up"] <= 0.25] or fits
    best = max(ok, key=lambda r: r["dd_cut"])
    print(f"chosen on fit: VIX>{best['cutoff']}")
    hold = run(spy, vix, best["cutoff"], start=SPLIT)
    full = run(spy, vix, best["cutoff"])
    show("hold", hold)
    show("full", full)
    print(f"PASS TEST (full 10y, as pre-registered): {'PASS' if passes(full) else 'FAIL'}"
          f" (needs drawdown cut >= 20%, give-up <= 25%, >= 3 episodes)")

    # Macro-event short: short SPY at the close before a FOMC decision / jobs Friday, cover at the event-day close.
    fomc = pd.to_datetime("""2017-02-01 2017-03-15 2017-05-03 2017-06-14 2017-07-26 2017-09-20 2017-11-01 2017-12-13
    2018-01-31 2018-03-21 2018-05-02 2018-06-13 2018-08-01 2018-09-26 2018-11-08 2018-12-19 2019-01-30 2019-03-20
    2019-05-01 2019-06-19 2019-07-31 2019-09-18 2019-10-30 2019-12-11 2020-01-29 2020-04-29 2020-06-10 2020-07-29
    2020-09-16 2020-11-05 2020-12-16 2021-01-27 2021-03-17 2021-04-28 2021-06-16 2021-07-28 2021-09-22 2021-11-03
    2021-12-15 2022-01-26 2022-03-16 2022-05-04 2022-06-15 2022-07-27 2022-09-21 2022-11-02 2022-12-14 2023-02-01
    2023-03-22 2023-05-03 2023-06-14 2023-07-26 2023-09-20 2023-11-01 2023-12-13 2024-01-31 2024-03-20 2024-05-01
    2024-06-12 2024-07-31 2024-09-18 2024-11-07 2024-12-18 2025-01-29 2025-03-19 2025-05-07 2025-06-18 2025-07-30
    2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18 2026-04-29 2026-06-17 2026-07-29 2026-09-16
    2026-10-28""".split())
    months = pd.date_range(spy.index[0], spy.index[-1], freq="MS")
    nfp = [m + pd.offsets.Week(weekday=4) if m.weekday() != 4 else m for m in months]
    for name, dates in [("FOMC", fomc), ("jobs Friday", nfp)]:
        rets = []
        for d in dates:
            if d not in spy.index:
                continue
            i = spy.index.get_loc(d)
            if i < 1:
                continue
            rets.append(-(spy.iloc[i] / spy.iloc[i - 1] - 1) * 100 - 0.10)
        rets = np.array(rets)
        print(f"Macro short into {name}: {len(rets)} trades, avg {rets.mean():+.3f}%/trade after 0.10% cost,"
              f" win {np.mean(rets > 0):.0%}, total {rets.sum():+.1f}%")


if __name__ == "__main__":
    main()
