"""Strategy rebuild, Phase 5a — the honest dip-buying backtest that gates the long-term core bucket.

Universe: point-in-time S&P 500 membership, rebuilt from today's list and Wikipedia's change log (so companies that
were removed — bought out, shrank, failed — are members on the dates they were members).
Health check (pass/fail, as of each date): positive net income and free cash flow, revenue not shrinking more than
5%, debt <= 6x free cash flow, price > $10; banks/insurers/REITs (Financials, Real Estate) excluded. Fundamentals come
from SEC XBRL frames; a year's annual numbers count only from April 1 of the next year (after 10-K filing), so the
test never knows a number before it was public.
Triggers: SPY down 10%, 20%, 30% from its 52-week high, each once per downturn; a downturn ends at a new SPY high.
At each trigger, buy equal weights of healthy members down >= 20% from their own 52-week high, hold 12 months, and
compare with buying SPY (VFV) at the same moment.

Survivorship handling — members removed during the 12-month hold whose prices Yahoo no longer serves:
  realistic: buyout -> 0% (deal price not extracted; buyouts are usually at a premium, so this is conservative);
             bankruptcy / FDIC receivership / shrank / unknown -> -100%
  all-zero:  every such member -> -100%
They are assumed to have qualified (the harsh direction). Members with prices whose series ends early (bought out,
delisted) use their last price.

Pre-registered pass: realistic beats VFV by >= 2 points a year AND all-zero isn't worse than VFV by more than 2 points
a year, over >= 3 separate downturns.

Usage: python -I phase5_core_backtest.py SCRATCH_DIR
"""
import glob
import json
import os
import re
import sys

import numpy as np
import pandas as pd

HOLD = 252
DIP = -0.20
TRANCHES = [-0.10, -0.20, -0.30]
MIN_PRICE = 10
EXCLUDED_SECTORS = {"Financials", "Real Estate"}


def load_prices(s):
    a = pd.read_csv(f"{s}/ohlc.csv", parse_dates=["candle_date"])
    b = pd.read_csv(f"{s}/dl5/extra_px.csv", parse_dates=["candle_date"])
    df = pd.concat([a, b]).drop_duplicates(["ticker", "candle_date"])
    return df.pivot(index="candle_date", columns="ticker", values="close").sort_index()


def load_frames(s):
    """{concept: {(cik, year): value}}"""
    out = {}
    for path in glob.glob(f"{s}/dl5/frames/*.json"):
        name = os.path.basename(path)[:-5]
        concept, period = name.rsplit("_", 1)
        year = int(re.match(r"CY(\d{4})", period).group(1))
        data = json.load(open(path)).get("data", [])
        d = out.setdefault(concept, {})
        for row in data:
            d[(int(row["cik"]), year)] = row["val"]
    return out


def healthy(cik, year, f):
    def v(c):
        return f.get(c, {}).get((cik, year))
    ni = v("NetIncomeLoss")
    ocf = v("NetCashProvidedByUsedInOperatingActivities")
    capex = v("PaymentsToAcquirePropertyPlantAndEquipment") or 0
    rev = v("Revenues") or v("RevenueFromContractWithCustomerExcludingAssessedTax")
    prev = f.get("Revenues", {}).get((cik, year - 1)) or f.get("RevenueFromContractWithCustomerExcludingAssessedTax", {}).get((cik, year - 1))
    debt = v("LongTermDebt") or v("LongTermDebtNoncurrent")
    if ni is None or ocf is None:
        return None  # unknown
    fcf = ocf - capex
    if ni <= 0 or fcf <= 0:
        return False
    if rev is not None and prev and rev < 0.95 * prev:
        return False
    if debt is not None and debt > 6 * fcf:
        return False
    return True


def main():
    s = sys.argv[1]
    px = load_prices(s)
    spy = px["SPY"].dropna()
    px = px.reindex(spy.index)
    frames = load_frames(s)
    cur = pd.read_csv(f"{s}/dl5/current.csv")
    ch = pd.read_csv(f"{s}/dl5/changes.csv", parse_dates=["date"])
    rem = pd.read_csv(f"{s}/dl5/removed.csv", parse_dates=["date"])
    status = pd.read_csv(f"{s}/dl5/extra_status.csv").set_index("ticker")["status"].to_dict()
    cik = dict(zip(cur.Symbol, cur.CIK))
    sector = dict(zip(cur.Symbol, cur["GICS Sector"]))

    def kind_of(reason):
        r = str(reason).lower()
        if re.search(r"bankrupt|chapter 11|fdic|receivership", r):
            return "BANKRUPT"
        if re.search(r"acqui|merg|bought|purchase|taken private|buyout|spun|spin", r):
            return "BUYOUT"
        if re.search(r"market cap|capitaliz", r):
            return "SHRANK"
        return "UNKNOWN"

    removal = {r.rem_t: (r.date, kind_of(r.reason)) for r in rem.itertuples()}

    def members(t):
        m = set(cur.Symbol)
        for r in ch[ch.date > t].itertuples():
            if isinstance(r.add_t, str):
                m.discard(r.add_t)
            if isinstance(r.rem_t, str):
                m.add(r.rem_t)
        return m

    # triggers
    hi = spy.rolling(252, min_periods=60).max()
    dd = spy / hi - 1
    triggers, fired, downturn = [], set(), 0
    for d, v in dd.items():
        if v >= -1e-9 and fired:
            fired, downturn = set(), downturn
        for k in TRANCHES:
            if v <= k and k not in fired:
                if not fired:
                    downturn += 1
                fired.add(k)
                triggers.append((d, k, downturn))
    triggers = [t for t in triggers if spy.index.get_loc(t[0]) + HOLD < len(spy)]
    print("triggers:", [(str(d.date()), f"{k:.0%}", n) for d, k, n in triggers])

    stock_hi = px.rolling(252, min_periods=120).max()
    rows = []
    for d, k, n in triggers:
        i = spy.index.get_loc(d)
        end = spy.index[i + HOLD]
        fy = d.year - 1 if d >= pd.Timestamp(d.year, 4, 1) else d.year - 2
        spy_ret = spy.iloc[i + HOLD] / spy.iloc[i] - 1
        realistic, harsh, picked, ghosts = [], [], 0, 0
        for t in members(d):
            if sector.get(t) in EXCLUDED_SECTORS:
                continue
            has_px = t in px.columns and not np.isnan(px[t].iloc[i])
            if has_px:
                p0 = px[t].iloc[i]
                if p0 < MIN_PRICE or np.isnan(stock_hi[t].iloc[i]) or p0 / stock_hi[t].iloc[i] - 1 > DIP:
                    continue
                h = healthy(int(cik[t]), fy, frames) if t in cik and not pd.isna(cik[t]) else None
                if h is False:
                    continue
                series = px[t].iloc[i:i + HOLD + 1].dropna()
                r = series.iloc[-1] / p0 - 1  # last price if it stopped trading early
                realistic.append(r)
                harsh.append(r)
                picked += 1
            else:
                # a member Yahoo can't price: only matters if it died during this hold (the survivorship hole)
                if t not in removal:
                    continue
                rdate, kind = removal[t]
                if not (d < rdate <= end):
                    continue
                realistic.append(0.0 if kind == "BUYOUT" else -1.0)
                harsh.append(-1.0)
                ghosts += 1
        if not realistic:
            continue
        rows.append(dict(date=d.date(), tranche=k, downturn=n, picked=picked, ghosts=ghosts, spy=spy_ret,
                         realistic=np.mean(realistic), harsh=np.mean(harsh)))
    df = pd.DataFrame(rows)
    df["ex_real"] = df.realistic - df.spy
    df["ex_harsh"] = df.harsh - df.spy
    pd.set_option("display.width", 200)
    print(df.round(3).to_string(index=False))
    downturns = df.downturn.nunique()
    real, harsh = df.ex_real.mean() * 100, df.ex_harsh.mean() * 100
    print(f"\nrealistic: {real:+.1f} pts/yr vs VFV; all-zero: {harsh:+.1f} pts/yr; downturns: {downturns}")
    passed = real >= 2.0 and harsh >= -2.0 and downturns >= 3
    print(f"PASS TEST: {'PASS' if passed else 'FAIL'} (needs realistic >= +2, all-zero >= -2, >= 3 downturns)")
    return passed


if __name__ == "__main__":
    main()
