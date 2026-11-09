# Argus strategy rebuild — build plan

Agreed in a brainstorm on 2026-11-08 and 2026-11-09, after the blank-slate review found that Agent 5's calls were
inverted (BUY → −15% over the next 20 trading days, STRONG_AVOID → +8%) and that the feedback loop learned from only
11 trades. Four points were locked; this plan builds them in dependency order.

**Ground rules for every phase**
- Nothing touches real trading until it has passed a **pre-registered** test. The pass criteria are written into this
  plan *before* the phase is built and are not changed after seeing results.
- Every new rule is tuned on older data and checked on newer data it has never seen, and only a few cutoffs are
  tried per rule (no curve-fitting sweeps).
- Shadow results and report cards keep running even when real trading is cut back.
- Each phase ends with tests, a commit and a deploy to the Mini. Each deploy is approved by the user.
- **Independent samples, not raw counts.** Overlapping daily calls on the same stock, and stocks called on the same
  day in the same sector, move together. Every sample-size gate counts **clusters (date × sector)**, the way
  `LessonMiner` already does, and a forward window is counted once per non-overlapping period.
- **Every pass test states a minimum size and a margin.** "New beats old" must clear both; anything smaller is
  noise, and the old behavior stays.
- **Every live phase has a way back:** a feature flag, a pre-set automatic switch-off trigger, and the replaced code
  kept behind the flag for at least 2 months before it's deleted.
- **Use the waiting time:** while Phase 1 waits on calendar time, Phases 3 and 4 are built and backtested, but
  nothing goes live until Phase 1 passes.

---

## Phase 0 — Foundations (no behavior change)
1. **Forward-return grader** (new `learning/grading` package). For every agent signal, keep **one prediction per
   agent per stock per day** (the last one) and score it at 5, 10, 20 and 60 trading days as **excess return vs
   SPY**. Situation tags come from the existing `recommendations.features` tokens (regime, trend, vol, sector,
   earnings proximity) and record the signal's stated strength. Run it nightly and backfill from 2026-09-25.
2. **Benchmark history.** `price_candles` has only 1y of SPY, QQQ, VIX and the held tickers. Extend those to 10y
   (Yahoo) so the backtests can run against the real benchmark.
3. **Realistic fill model**, shared by paper and shadow trades: spread and slippage scaled to the stock's normal
   range, a commission, and gaps through a stop fill at the gap price (already true for the hard stop).
4. **Pre-registered "worth acting on" bar**, stamped on the recommendation row at creation time.

## Phase 1 — Report card drives the weights (Point 1)
1. **Report card per agent:**
   - an overall grade, at the agent's own timeframe (news/social/calendar 5d, technical 10d, macro/filings/deep
     20d, fundamentals/academic 60d), with every timeframe recorded
   - situation grades for market mood and earnings week, blended toward the overall grade until each situation has
     enough data (shrinkage)
   - a **confidence check**: if an agent's strong calls aren't more often right than its weak ones, its strength is
     ignored and its trust is lowered
2. **Weights from the report card:**
   - good record → up to 2×; coin flip → reduced; bad → muted to zero
   - flipped only when reliably wrong across different market moods
   - weights move a little each night, never in one jump
   - no weight moves until an agent has **≥ 30 independent date × sector clusters** at its timeframe
   - manual pin/override per agent
   - **"No grade yet" ≠ "bad":** an ungraded agent keeps its current weight. With data only from 2026-09-25, the
     60-day agents (Fundamentals, Academic) get their first grade around **2026-12-19** (60 trading days later).
     The 20-day agents are gradable now.
3. **Pass test, two parts (pre-registered):**
   - **Backfill check (available now).** Fit the weights on calls from 2026-09-25 to 2026-10-16, then score them on
     2026-10-19 onward. The new weights must beat the live weights by **≥ 1.0 point of average 20-day excess
     return**, have a positive BUY − AVOID spread, and do so on **≥ 30 independent clusters**.
   - **Forward check.** Agent 5 runs with the new weights in shadow for 2 weeks; those calls are graded once their
     20-day windows close (about 2 weeks + 20 trading days). Same margin and minimum size.
   - The switch to live waits for the forward check. If the backfill check fails, stop and rethink before building
     on it.
4. **On pass:** switch over. The old `AdaptiveTuningService` multipliers and `LogicReviewService` are **disabled
   behind a flag** (deleted only after 2 months live).
   **Automatic switch-off:** if the live calls' rolling 20-day BUY − AVOID spread is negative over ≥ 30 clusters,
   or the paper book falls ≥ 8% below SPY since the switch, revert to the old weights and push a CRITICAL alert.
5. **Agents page:** report cards in plain words, with a sample size next to every score.

## Phase 2 — Shadow trades, post-mortems, lessons (Point 1, continued)
1. **Shadow book.** Every call over the pre-registered bar gets a shadow trade with the same entry, stop and target
   rules and the realistic fills. Shadow trades ignore cash and position limits, so the **shadow − real gap** is
   tracked as "what the portfolio rules cost or save".
2. **Post-mortem on every real and shadow trade**, decided by numbers only. **One measure for everything: return
   vs SPY over the same period** (direction-adjusted). "Win" = beat SPY. A trade that lost 3% while SPY lost 8% is
   a win. Rules are checked **in this order**, and the first match decides, so every trade lands in exactly one
   bucket:

   | # | Bucket | Rule |
   |---|---|---|
   | 1 | Bad idea, lucky win | Beat SPY, but the agents it relied on were wrong on their own timeframe |
   | 2 | Good idea, good result | Beat SPY (and rule 1 didn't match) |
   | 3 | Good idea, bad execution | Lagged SPY, but holding to the planned date would have beaten SPY (`hold_return_pct`) |
   | 4 | Good idea, bad luck | Lagged SPY, and the loss came from a shock (market-shock day, unscheduled event, gap > 2× normal range) |
   | 5 | Bad idea | Everything else |

   Plus a per-agent check of each signal at entry vs what the stock did (the same grader as Phase 0). The
   templated, no-LLM `LessonComposer` gets extended. An LLM may phrase the summary but never classifies.
3. **Agent 13 (`LessonMiner`):** mines patterns on post-mortem buckets × situation. Lookalike trades are clustered
   by date and sector before counting toward the 3–4 repeats (clustering already exists). A **new `PROBATION`
   state** means a rule that passes the hold-out test still runs silently on the next few weeks of fresh trades
   before it goes ACTIVE.
4. **Agents page:** shadow vs real side by side, and clickable bucket counts that open the trades behind them.

## Phase 3 — Don't chase run-ups (Point 2)
1. **"Stretch"** = (price − 20-day average) ÷ normal daily range (ATR). Try 2, 3 and 4 as cutoffs only: fit on the
   10y universe before 2023, check on 2023 onward and on the post-2026-09-25 calls.
2. **Stretched BUY → `WAIT_FOR_PULLBACK`** with a buy zone (near the 20-day average or support, via
   `PriceGuidance`/`ChartStudyService`). It alerts and paper-buys only inside the zone, and expires after ~2 weeks.
   **Calls that expire unfilled are logged**, and their later return is tracked as the rule's cost.
3. **Exception:** fresh hard news (earnings beat or raised guidance, or a strong Agent 14 filing, in the last few
   days) allows the buy at a smaller size. Hype, social or trending never counts.
4. **Mirror rule:** no AVOID/SELL on a stock stretched far below normal unless there's fresh hard bad news; it
   becomes WATCH instead.
5. **Pass test:** in the hold-out window, on **≥ 30 independent clusters**, the rule improves the 20-day excess
   return of BUY calls by **≥ 1.0 point**, and the expired (unfilled) calls don't outperform the filled ones.
6. **Survivor caveat:** the 10y Yahoo universe only holds companies that still exist. That matters less when
   comparing two rules than for dip-buying, but it's noted, and this backtest is re-run once Phase 5a's list of dead
   companies exists.
7. **Automatic switch-off:** if expired calls outperform filled calls by ≥ 2 points over ≥ 30 clusters live, the
   rule is turned off and an alert is sent.

## Phase 4 — Defense in bad markets (Point 4)
1. **Two-level regime** in `MarketRegimeService`:
   - **Caution** = SPY below its 200-day average OR VIX above the cutoff
   - **Full defense** = both
   - the state switches only after a few days in a row
   - VIX cutoffs tested at 20, 25 and 30 only
2. **Trading bucket in defense:** smaller sizes (caution = a small cut, full = half), a higher entry bar, no new buys
   in the weakest sectors. **Stops stay based on each stock's own normal movement** and are not tightened.
3. **No new trades the day before** a Fed decision, CPI or jobs report, or the stock's own earnings.
4. **Macro-event short** runs as a shadow strategy for **12 releases**, then is dropped unless it beats zero after
   costs.
5. **Single-stock shorts** need fresh hard bad news plus a broken trend, and are never placed on a stock stretched
   below normal. Guardrails: small size and a hard max loss per short, skip heavily shorted stocks (FINRA's free
   short-interest data), and charge a borrow cost.
6. **Backtest (10y):** measure **max drawdown and time to recover**, not just total return.
   **Pass test:** max drawdown cut by **≥ 20%** (relative) while giving up **≤ 25%** of total return, over at least
   3 separate defense episodes. Same survivor caveat as Phase 3, re-checked after Phase 5a.
7. **Automatic switch-off:** if defense mode has been on ≥ 60 trading days in a row while SPY is above its level
   when defense started (defense costing money in a recovery), push an alert for review. The regime switch itself
   stays mechanical.

## Phase 5 — Long-term core bucket (Point 3)
**5a. Data + the honest backtest (a gate: if it fails, the core bucket is not built)**
1. Ingest SEC EDGAR company facts (free, keyless), using **filing dates, not quarter-end dates**, and **including
   companies that no longer exist**.
2. For each vanished company, classify buyout vs bankruptcy from its filings. Buyouts use the deal price (read from
   the merger filing with the existing local-Gemma extraction plus cross-check); bankruptcies go to zero.
3. Re-run the dip-buying test twice. **Pass:** the realistic version beats just holding VFV by **≥ 2 points a
   year**, and the all-zero version isn't worse than VFV by more than 2 points a year, over **≥ 3 separate market
   downturns**. Otherwise the core bucket is not built.

**5b. Core engine (paper, in shadow)**
1. **Health check, pass/fail:** positive earnings and free cash flow, manageable debt, revenue not shrinking, price
   over $10 and a reasonable size. Banks, insurers and REITs are excluded at first (VDY covers banks).
2. **Split:** 60% invested, 20% cash reserve, 20% trading bucket. The cash goes in a high-interest savings ETF.
3. **Ladder:**
   - spend 1/3 of the reserve each time SPY falls 10%, 20% and 30% from its 52-week high
   - each tranche is spent once per downturn, and the reserve resets only at a new SPY high
   - refill from trims and new money only
   - if 18 months pass with no 10% drop, invest the reserve evenly over 6 months
4. **Limits:**
   - never buy past 10% of the core in one stock; trim if a position grows past 15%
   - max ~30% per sector
   - VFV + XQQ + mega-caps count as **one US-large-cap group**
   - max 3 buys per stock, with health re-checked before each
   - a monthly check moves money back to the split if it drifts
5. **Exit:** health check re-run after every quarterly filing; fail twice in a row → sell. A hard guidance cut or a
   going-concern warning → sell. No price stop.
6. **Wall, both ways:** trading signals can't add to the core or sell core holdings, and a trading position can
   never become a core holding.
7. **Real-portfolio alerts:** "accumulate" alerts with prices (building on the CORE_HOLD card style). Accounts get
   tagged TFSA, RRSP or taxable, so trim alerts point to the registered account first.
8. **Leaving shadow (pre-registered):** after 3 months, or one real 10% SPY drop, whichever comes first, the core is
   compared with just holding VFV. **Pass:** return no worse than VFV, and a smaller max drawdown or faster
   recovery.
9. **Automatic switch-off:** if the live core trails VFV by ≥ 5 points over any rolling 6 months, new ladder buys
   pause and an alert asks for review. Existing holdings are never force-sold by this trigger.

---

**Why this order:** Phase 0–1 is the measuring stick everything else is judged with. Phases 3–4 are small rules on
the existing engine. Phase 5 needs a new data source and passes a backtest gate before any engine is built.

**Timeline shape:** Phase 0 → Phase 1 backfill check → Phase 1 forward shadow (about 2 weeks + 20 trading days of
waiting). Phases 2, 3 and 4 are built and backtested during that wait and go live only after Phase 1 passes.
Phase 5a's data work can also start during the wait.

**Model experiment (added 2026-11-09):** inside Phase 1, run one LLM-driven agent (News or Deep Analysis) on a small
daily sample with a stronger Claude model (via the existing API key) alongside Gemma, and let the report card decide
whether it predicts better. The Claude CLI/subscription route was considered and rejected: tiny cost savings
(API spend is about $2–7/month), shared usage limits, and it's not meant to power a multi-user service.

**Open items:** paid delisted-price data (Norgate/Sharadar) only if the EDGAR approach proves too thin; the exact
"set share of return" for the Phase 4 pass test is to be fixed before that backtest runs.

---

## Outcomes (built 2026-11-09)

| Phase | Result |
|---|---|
| 0 — Foundations | **Built.** Nightly grader (`grading/SignalGrader`), realistic fills (`FillModel`, plus a 1%/yr borrow cost on shorts), stamped actionable bar, 10y SPY/QQQ/^VIX history. |
| 1 — Report card | **Built, in shadow.** `ReportCardMath`/`ReportCardService`/`ReportCardWeights`; every call stamps a shadow verdict; `Phase1Gate` runs the backfill check (fit ≤ 2026-10-16, test ≥ 2026-10-19 — gradable from about 2026-11-17) and the forward check, and goes live only if both pass. Switch-off reverts automatically. Logic review stands down while it's live. Model experiment: `exp-news-claude` vs `exp-news-gemma` on the same daily sample. |
| 2 — Shadow trades, post-mortems | **Built.** `ShadowBook` (same `PositionRules`, gap-aware stops), `PostmortemService` (5 buckets, fixed order, all vs SPY), Agent 13 learns from shadow trades and puts new rules on **probation** until fresh trades confirm them. |
| 3 — Don't chase | **Failed its backtest → not built as a rule.** Buy side: hold-out gain −0.01 pts (1,581 clusters), expired calls beat filled ones. Mirror: −0.02 pts. Only 1–2 of Agent 5's 28 graded BUYs were stretched. A `stretch=` feature token lets the learner find it if it matters for Argus specifically. (`research/phase3_stretch_backtest.py`) |
| 4 — Defense | **Defense failed narrowly → not built.** Max drop cut 30% (bar ≥ 20%) but 28% of return given up (bar ≤ 25%). Macro short: −11.6% over 78 FOMC days, −22.2% over 113 jobs Fridays; runs as a 12-release shadow that drops itself. Calls the day before FOMC/jobs carry a `macro=` token for the learner. Not built: weakest-sector filter, short-interest skip (no data source), CPI dates (no schedule source). (`research/phase4_defense_backtest.py`) |
| 5 — Core bucket | **Failed its gate → engine not built.** Survivorship-honest test lagged VFV by 1.5 pts/yr (all-zero: −8.4). Buyout deal prices weren't extracted (counted as 0%, conservative); membership came from Wikipedia's S&P 500 change log. Informational: a 25% cash ladder into VFV returned +283–288% vs +304% fully invested over 10y. (`research/phase5_core_backtest.py`) |

## Change log

**2026-11-10 — grading robustness, and News / Chart Reader score against their own normal.**
Why: the first report cards muted Chart Reader (−10.4 pts) and News (−1.5 pts). Digging in:
- One 80¢ stock (RETO, +402% in ten days) that Chart Reader called bearish 14 times decided most of its grade.
  Without it, or with every result capped at ±30 pts, its spread was −0.6 pts instead of −5.5.
- The 44-stock universe trailed SPY (semis −10.5, AI infra −18.8 pts at 10 days); even News's neutral calls trailed by
  9.5 pts. Against same-sector peers neither agent was badly wrong.
- Tested on the graded calls (results capped, sub-$5 dropped), the signal *minus its own trailing normal* correlated
  with 10-day excess in both halves of the period (Chart Reader +0.15 / +0.36; News +0.23 / +0.23 at its 5-day
  window). Sector-relative scoring helped Chart Reader but hurt News, and combining both was unstable — not adopted.

What changed:
1. Report-card grading (live cards and the Phase 1 backfill fit): each result capped at ±30 pts; stocks under $5 at
   entry are not graded. The Phase 1 *pass test itself* (rec_grade calls, margin, spread, clusters) is unchanged.
2. News votes on this week's tone minus the stock's tone over the 30 days before (raw tone if fewer than 5 stories).
3. Chart Reader votes on today's chart score minus its average over the prior 20 sessions, each re-read from candles
   as they stood (level as before if there's too little history); vote bar 0.1, full weight at 0.4.
4. News's and Chart Reader's live report cards restart on 2026-11-10 — older grades describe the old signals.
   They're UNGRADED (weight back toward 1.0, 0.1 a night) until 30 fresh clusters (~5–6 weeks).

Caveat: six weeks of overlapping data; the correlations are promising, not proof. The restarted cards are the test.
