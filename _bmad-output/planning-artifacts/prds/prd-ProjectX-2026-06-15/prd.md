---
title: "Argus — AI-Powered Personal Investment Intelligence Platform"
status: draft
created: 2026-06-15
updated: 2026-06-15
project: Argus
author: Gaurav.oli
version: 0.1
---

# PRD: Argus
## AI-Powered Personal Investment Intelligence Platform

---

## 0. Document Purpose

This PRD is written for Gaurav (solo builder, sole user) and downstream workflow owners in the BMAD stack — primarily `bmad-create-architecture`. It defines what Argus must do, not how to build it. Technology choices confirmed in brainstorming are captured in §10 (Technology Constraints) as guardrails, not implementation prescriptions. Architecture and mechanism decisions belong to the architecture phase.

The document is structured as: Glossary → Features (FRs nested) → NFRs → MVP scope → Success metrics. `[ASSUMPTION]` tags mark inferences not explicitly confirmed in brainstorming; all assumptions are indexed in §9. Open questions carry over from brainstorming and are listed in §8.

**Source of truth:** `_bmad-output/planning-artifacts/brainstorming/brainstorming-session-2026-06-11-2231.md`
**Decision audit trail:** `.decision-log.md` in this folder.

---

## 1. Vision

Argus is a self-hosted AI investment intelligence platform built for a single investor — me. Named after the Greek all-seeing giant with 100 eyes who never slept, it runs 7 AI agents around the clock on a local Mac Mini, watching markets, news, social media, and financial filings so I don't have to. It aggregates everything those agents learn, routes it through a strategy engine that earns its trust before I follow it, and surfaces the result as a daily morning briefing, real-time push notifications, and probability-based recommendations — not binary buy/sell calls.

The core insight driving the design is that most retail investors fail not from lack of data, but from too much noise, hidden biases, and no way to challenge their own thinking before they act. Argus attacks all three: alert fatigue management silences noise until signals align; 4 AI personas argue every recommendation from opposing viewpoints; conversational AI lets me interrogate any analysis before acting. The platform earns trust over time — Agent 5 (the strategy engine) starts in Shadow Mode and only surfaces recommendations after demonstrating calibrated, reliable judgment.

**What Argus is — and is not.** Argus is a *decision-support and discipline system*, not an alpha-generation engine. Public news and social sentiment are priced in by institutional players within seconds; a locally-hosted platform reading free APIs on a multi-minute cycle is structurally behind professional order flow and will not find market-beating edges through superior signal speed. Its real value is **behavioral**: keeping me informed without overwhelming me, forcing me to confront the bear case before I act, managing position risk, and stopping me from making the impulsive, biased decisions that actually cost retail investors money. Argus succeeds if it makes me a more disciplined, better-informed, less-biased investor — not if it "beats the market."

The platform runs entirely on local hardware, costs under $15 USD/month to operate, and is accessible from anywhere via Tailscale. By the end of MVP it will deliver more daily value than any free investing tool available — and the data never leaves the home network.

---

## 2. Target User

### 2.1 Jobs To Be Done

- **Monitor without watching** — know the moment something moves that affects my portfolio without staring at screens.
- **Get second opinions automatically** — have multiple investor philosophies challenge every recommendation before I decide.
- **Ask follow-up questions** — interrogate any analysis or recommendation in plain language with full context.
- **Stay informed, not overwhelmed** — receive one clean, high-confidence alert at the right moment, not a hundred low-confidence pings.
- **Know when NOT to act** — be warned when market conditions make confidence claims dangerous (Black Swan protection).
- **Track whether the platform is actually right** — know Agent 5's win rate before following its recommendations.
- **See everything that's relevant to me as a Canadian investor** — CAD/USD impact, TFSA/RRSP context, TSX stocks.
- **Sleep knowing the platform is healthy** — confirm that agents are running, data is fresh, budget is within range.

### 2.2 Non-Users (v1)

- Other investors (this is a personal, private, single-user platform — no multi-tenancy, no sharing, no accounts)
- Professional traders requiring sub-second execution or Level 2 order flow
- Anyone expecting fully automated trading (platform recommends; human decides and executes)

### 2.3 Key User Journeys

- **UJ-1. Gaurav checks his morning briefing before the market opens.**
  Gaurav, solo investor working full time, wakes up and sees an iPhone push notification at 8am: "Argus Morning Briefing — 3 things to watch today." He taps it and the Argus PWA opens directly to the Morning Briefing card pinned to the top of the dashboard. The card shows: overnight portfolio changes (+1.2%), one Agent 5 recommendation waiting (NVDA, bullish, 68% probability), and two upcoming events (Fed statement at 2pm, NVDA earnings in 3 days). He reads the briefing in under 2 minutes, taps the NVDA recommendation card, then taps "Get Perspectives" to see what Warren Buffett and Devil's Advocate think. Satisfied, he marks it "Watch" and closes the app. **Resolution:** Briefing is collapsed, portfolio state shows new price data, recommendation stays in Watch state. **Edge case:** If overnight internet outage occurred, briefing shows Degraded Mode banner with "Stale data from 11pm — agents resuming" and last-known prices.

- **UJ-2. An urgent alert fires on Gaurav's iPhone during a market sell-off.**
  Gaurav is away from home. His iPhone vibrates with a 🔴 Critical push notification: "Argus Alert — Portfolio down 8.3%. Black Swan Warning active." He taps it; the PWA opens to a full-screen alert. Agent 5's confidence is capped at 60% maximum with a mandatory Black Swan Warning. The recommendation reads: "Wait and observe — current market conditions make high-confidence calls unreliable." Gaurav taps "Ask AI" and types: "Should I sell NVDA now or hold?" The platform responds in 12 seconds, grounded in his specific portfolio and the current signals. He decides to hold, taps Dismiss on the alert, and goes back to his day. **Resolution:** Alert acknowledged, no action taken, platform continues monitoring.

- **UJ-3. Gaurav checks the System Operations Dashboard after a week away.**
  Gaurav returns from a trip and wants to verify the platform was healthy while he was away. He navigates to the Agents tab → Operations. The dashboard shows all 7 agents green except Agent 3 (which is Phase 2 — not yet built). He sees RAM at 24.2 / 28GB, SSD at 47 / 256GB, and monthly spend at $9.40 USD (of $75 budget). He spots that Agent 4 had 2 errors on Tuesday — taps to see the error log ("Finnhub rate limit hit — auto-retried at 60s interval, recovered"). He checks backup status: last successful backup was 14 minutes ago. **Resolution:** Platform confirmed healthy, errors were self-recovered, he continues to dashboard.

---

## 3. Glossary

- **Agent** — An autonomous background process that continuously collects, processes, or analyzes a specific data domain (news, social, financial reports, etc.) and publishes its output to the shared data layer. Argus has 7 agents, numbered 1–7. Agents collect and analyze; they never act on the outside world without user approval.
- **Agent 5** — The Strategy Agent. The only agent that generates trade Recommendations. Operates in one of four Graduation States.
- **Graduation State** — The current trust level of Agent 5: Shadow (paper-trading only, not shown to user), Probation (shown with UNVALIDATED badge), Active (full recommendations), or Frozen (self-diagnosis mode, no new recommendations).
- **Recommendation** — A probability-based assessment from Agent 5 for a specific stock, expressed as a Probability Forecast Card. Never a binary BUY/SELL.
- **Probability Forecast Card** — The standard Recommendation format: bull probability %, bear probability %, price target, time horizon, confidence score, signal alignment from all 7 agents, and quick Approve/Watch/Dismiss actions.
- **Multi-Signal Diagnostic Report** — The attribution report attached to every Recommendation showing which agents contributed, their individual assessments, and any conflicting signals.
- **Portfolio Health Score** — A single 0–100 score summarizing the overall health of the portfolio, calculated daily from diversification, risk concentration, position sizing, and agent sentiment signals. Every point deduction is explained.
- **Morning Briefing** — A daily AI-generated summary delivered at 8am via push notification, containing overnight events, portfolio changes, upcoming calendar events, and top 3 priority actions.
- **Alert Fatigue Management** — The scoring gate that suppresses agent alerts below a combined confidence + portfolio impact threshold before any push notification fires.
- **Black Swan Warning** — A system-wide warning that triggers when market conditions are extreme (market down 5%+ in one day, VIX above 35, or 3+ markets simultaneously down 3%+). When active, Agent 5 confidence is capped at 60% maximum.
- **Source Credibility Score** — A 0–100 score assigned to every external data source by Agent 1. Scores improve when a source's signals lead to correct outcomes and degrade on wrong outcomes. Sources below 10/100 are automatically blocked.
- **Degraded Mode** — The platform's operational state during internet outage. Agents 1, 2, 3, 4, 7 pause; Agents 5 and 6 continue on cached data; the local Gemma model handles all AI queries; platform displays stale-data warnings.
- **Persona** — An AI investor archetype (Warren Buffett, Devil's Advocate, Peter Lynch, Canadian Investor) that analyzes every Recommendation through its specific investment philosophy. Powered entirely by the local Gemma model at zero API cost.
- **Ask AI** — The Conversational AI interface available on every Recommendation card, analysis report, and the main portfolio dashboard. Answers are grounded in the user's specific portfolio and the exact signals driving the analysis.
- **Cost Governor** — The budget protection subsystem within Agent 6 that tracks real-time API spend, sends notifications at 70%/80%/95% thresholds, and auto-switches to local Gemma if Gaurav doesn't respond within the notification window.
- **Tailscale** — The zero-config mesh VPN that makes the platform accessible from iPhone/iPad anywhere globally without exposing it to the public internet.
- **PWA** — Progressive Web App. Argus's mobile delivery format (iPhone + iPad), served from the Next.js frontend. Enables push notifications, home screen installation, and offline display — without an App Store presence or Apple Developer fee.

---

## 4. Features

> FRs are numbered globally (FR-1 through FR-N) for stable downstream reference. Phase 1 = MVP. Phase 2 and Phase 3 features carry enough detail to inform the architecture but their FRs will be expanded in future PRD update passes.

---

### F1. Portfolio Management & Real-Time Monitoring
**Phase:** MVP
**Description:** The core daily-use surface. Gaurav uploads his portfolio once (via PDF statement) and from that point forward the platform maintains it: tracking real-time prices, calculating current value, profit/loss per position, and displaying everything in a TradingView-powered chart. Portfolio value updates on every price tick — no page refresh, no stale data. Positions can be updated manually or via re-upload; brokerage API connection is a Phase 3 optional enhancement. Realizes UJ-1.

**Functional Requirements:**

#### FR-1: Portfolio Upload via PDF
Gaurav can upload a brokerage statement PDF and the platform parses it to extract holdings (ticker, shares, cost basis, acquisition date). Realizes UJ-1.
**Consequences:**
- Parsed holdings are displayed in the holdings table within 60 seconds of upload.
- Fields that cannot be parsed from PDF are flagged for manual entry; upload does not fail silently.
- Previously confirmed positions are not overwritten without explicit user confirmation.
**Out of Scope:** Automatic brokerage API sync (Phase 3 optional).

#### FR-1b: Adjusted Cost Base (ACB) with Purchase-Time FX
For each position (and each lot, where lot data is available), the platform records the cost basis in the original trade currency AND the CAD-equivalent cost basis using the USD/CAD rate at the time of purchase — not today's rate.
**Consequences:**
- For US-listed holdings bought in CAD, true P&L and tax-relevant gain/loss are computed against the CAD cost base at purchase FX, never against a live-converted USD cost basis.
- Where purchase-time FX is unknown (e.g., from a PDF that omits it), the platform prompts Gaurav for the rate or date and flags the position as "FX estimated" until confirmed.
- ACB is maintained per Canadian tax convention (weighted-average cost across lots of the same security). [ASSUMPTION: weighted-average ACB, not lot-specific/FIFO — confirm; Canada requires ACB averaging for non-registered accounts]
**Notes:** This is the foundation for the Phase 3 Year-End Tax Summary (F41). Getting it right from day one avoids a painful retrofit.

#### FR-1c: Corporate Actions Handling
The platform detects and applies corporate actions — stock splits, reverse splits, special/stock dividends, ticker symbol changes, and mergers/acquisitions — to held positions.
**Consequences:**
- On a split, share count and per-share cost basis are adjusted automatically so total cost basis and historical P&L remain correct; Gaurav is notified of the adjustment.
- On a ticker change or merger, the position is re-mapped and historical data (news, prices, recommendations) is linked across the old and new symbols.
- Unhandled or ambiguous corporate actions raise a 🟡 Important alert for manual confirmation rather than silently corrupting the position.
- [ASSUMPTION: corporate-actions data sourced from Finnhub; gaps verified against SEC filings via Agent 4 in Phase 2]
**Feature-specific note:** Without this, the first split or ticker change silently breaks cost basis and P&L — a common, mundane failure mode that must not ship.

#### FR-2: Real-Time Portfolio Value
Gaurav can see the current total portfolio value and per-position P&L updating in real time during market hours.
**Consequences:**
- Portfolio value updates within 1 second of each price tick received via Finnhub WebSocket.
- Numbers animate smoothly (count up/down) — no jump cuts.
- After-hours prices are clearly labeled as after-hours with reduced visual prominence.
- All currency displays show CAD and USD equivalents using a recent FX rate. [ASSUMPTION: USD/CAD rate pulled from Finnhub (already in the API set) on an hourly refresh — FX does not move fast enough to need per-tick updates, and this avoids free-tier request-cap exhaustion. exchangerate-api.com free tier (~1,500 req/month) is insufficient for sub-hourly polling and is NOT used.]

#### FR-3: Holdings Table
Gaurav can view all positions in a sortable table showing: ticker, company name, shares, cost basis, current price, current value, day P&L ($), day P&L (%), total P&L ($), total P&L (%), portfolio weight (%).
**Consequences:**
- Table supports sort by any column.
- Color coding: green for gains, red for losses — consistent with the platform color system.
- Tap any row on mobile to expand position detail.

#### FR-4: Portfolio Chart (TradingView)
Gaurav can view a total portfolio value chart over selectable time ranges (1D, 1W, 1M, 3M, YTD, 1Y).
**Consequences:**
- Chart is powered by TradingView Lightweight Charts (free tier, no rebuild needed).
- Benchmark overlay (S&P 500, TSX) is deferred to Phase 3 (FR deferred, see §6.2).

#### FR-5: Manual Position Edit
Gaurav can add, edit, or remove individual positions manually without re-uploading the full PDF.
**Consequences:**
- Changes are reflected immediately in portfolio calculations.
- All manual edits are logged with timestamp in the audit trail.

**Feature-specific NFRs:**
- Price data latency: ≤1 second from Finnhub WebSocket during market hours.
- PDF parse accuracy: ≥95% of standard brokerage statement fields extracted without manual correction. [ASSUMPTION]

---

### F2. Portfolio Health Score
**Phase:** MVP
**Description:** A single 0–100 score visible at all times — the first number Gaurav sees when opening Argus. It summarizes everything the agents know about the portfolio's current health. Inspired by the credit score model: one number, every deduction explained, trend visible. Score changes daily; each change shows which signals moved it. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-6: Health Score Calculation
The platform calculates a Portfolio Health Score (0–100) daily [ASSUMPTION: recalculated at 6am, after Agent 4 and 7 overnight runs complete] incorporating: position concentration, sector diversification, agent sentiment signals, open risk alerts, pending critical actions.
**Consequences:**
- Score is always visible in the top bar on web and mobile.
- Color thresholds: 80–100 green, 60–79 amber, below 60 red.
- Score is never shown without a breakdown available (tap to expand).

#### FR-7: Score Breakdown with Point Deductions
Gaurav can tap the Health Score to see a full breakdown listing each deduction with a specific explanation and suggested fix.
**Consequences:**
- Every point deducted is accounted for — "−8 pts: Top 3 holdings are 72% of portfolio (concentration risk)" — not a vague category label.
- Suggested fixes are actionable and specific to his current holdings.
- Score history shows 30-day trend (improving / stable / declining).

---

### F3. AI News Intelligence — Agent 1
**Phase:** MVP
**Description:** Agent 1 runs on a 5-minute cycle, ingesting news from Finnhub, GDELT, and RSS feeds (Reuters, AP, Bloomberg, CNBC). For each article, it scores relevance to Gaurav's holdings, runs sentiment analysis via Llama 3.2 3B (local, free), and applies source credibility scoring before any signal is passed to Agent 5. Agent 1 also maintains the Source Credibility Engine — scoring sources 0–100 and blocking sources below 10/100. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-8: News Ingestion (5-minute cycle)
Agent 1 continuously ingests news from approved sources on a ≤5-minute cycle during market hours; ≤15-minute cycle outside market hours.
**Consequences:**
- Every ingested article is stored in MongoDB with: source, headline, body, publication time, ingestion time, source credibility score, sentiment score, and ticker relevance tags.
- Agent 1 never passes an article to Agent 5 if source credibility score < 10/100.

#### FR-9: Source Credibility Engine
Every news source is scored 0–100 and categorized: Platinum (90–100) / Gold (75–89) / Silver (50–74) / Bronze (25–49) / Flagged (10–24) / Blocked (<10).
**Consequences:**
- Unknown sources start at 35/100.
- Scores improve by +2 when a source's signals correlate with correct outcomes; degrade by −3 on wrong outcomes.
- Sources are automatically blocked when score drops below 10/100; Gaurav is notified.
- Agent 1 proactively discovers and evaluates new sources on a weekly cycle.

#### FR-10: Stranger Danger Protocol for Unknown Stocks
When Agent 1 detects significant coverage of a stock not in Gaurav's portfolio or watchlist, it applies elevated scrutiny: requires 6/7 agents aligned before Agent 5 can recommend, checks market cap, detects volume spikes and coordinated posting patterns, and generates a pump-and-dump risk score.
**Consequences:**
- Elevated threshold applies automatically — no configuration needed.
- Pump-and-dump risk score is shown in the Multi-Signal Diagnostic Report if a recommendation fires.

---

### F4. AI Recommendation Engine — Agent 5 (MVP)
**Phase:** MVP
**Description:** Agent 5 is the Strategy Agent — the only agent that generates trade Recommendations. It operates via a 4-mode Graduation System: it must earn trust before recommendations are surfaced to Gaurav. In MVP, Agent 5 runs on a hybrid trigger model: event-driven (fires when Agent 1 detects a high-impact signal) and scheduled (every 6 hours for routine portfolio review). All recommendations are expressed as Probability Forecast Cards with a Multi-Signal Diagnostic Report. Realizes UJ-1, UJ-2.

> Note: Full backtesting via QuantConnect is deferred to Phase 3 (see F30). In MVP, Agent 5 relies on signal convergence and historical price data for probability estimates.

**Functional Requirements:**

#### FR-11: Agent 5 Graduation System
Agent 5 operates in one of four states: Shadow → Probation → Active → Frozen. State governs whether recommendations are shown and how they are labeled.
**Consequences:**
- **Shadow Mode:** Agent 5 runs silently, records paper trades internally. Not shown to Gaurav. Graduates to Probation after achieving ≥70% win rate over ≥20 paper trades. [ASSUMPTION: paper trade tracked as correct if stock moves in predicted direction within the stated time horizon]
- **Probation Mode:** Recommendations are shown with a prominent UNVALIDATED badge. Graduates to Active after ≥10 real-world confirmed outcomes at ≥65% win rate.
- **Active Mode:** Full recommendations without special badge. Auto-demotes to Probation if rolling win rate drops below 50% over the most recent 10 confirmed trades.
- **Frozen Mode:** Auto-triggers on serious failure pattern [ASSUMPTION: defined as 3 consecutive losses while in Active Mode OR win rate drop below 40%]. No new recommendations shown. Self-diagnosis runs. Pending recommendations display a FROZEN warning — visible but not actionable. Agent 5 proposes fix → Agent 6 validates → Gaurav approves → drops to Shadow to re-earn trust.

#### FR-12: Probability Forecast Card
Every Agent 5 Recommendation is expressed as a Probability Forecast Card, never a binary BUY/SELL.
**Consequences:**
- Card shows: stock ticker + name, recommendation direction (bullish/bearish/neutral), bull probability %, bear probability %, price target, time horizon, overall confidence score (0–100), and signal alignment dots for all 7 agents.
- When Black Swan Warning is active, confidence is capped at 60% maximum and a mandatory Black Swan Warning banner is shown on the card.
- Card exposes three quick actions: Watch (save for later), Dismiss (not interested), and Ask AI (open Conversational AI in context of this recommendation).

**Probability provenance (critical design rule):**
- The bull/bear **probability percentages and the confidence score are computed by an explicit, auditable scoring model — NOT free-generated by an LLM.** An LLM asked for "68%" emits a plausible-sounding token with no calibrated basis; displaying that as a probability is misleading. [ASSUMPTION: MVP scoring model is a transparent rule/weight engine over agent signals — e.g., weighted signal-alignment count, source-credibility-weighted sentiment, and historical base rates — producing a score that is then mapped to a probability band. Exact model designed in architecture phase.]
- The **LLM's role is qualitative**: it writes the bull/bear narrative, explains the drivers in plain language, and surfaces risks — it does not invent the number.
- Every displayed probability must be **traceable to its inputs** (which signals, what weights) via the Multi-Signal Diagnostic Report (FR-13).
- Because the number is model-derived, it can be **calibration-tracked** over time (see FR-26b) — an LLM-generated number could not be meaningfully calibrated.

#### FR-13: Multi-Signal Diagnostic Report
Every Recommendation includes an attached Multi-Signal Diagnostic Report showing which agents contributed, their individual assessments (bullish / bearish / neutral), any conflicting signals, and data source attribution.
**Consequences:**
- Report is collapsed by default; tap to expand on the Recommendation card.
- Every agent's contribution to the recommendation is shown individually — no black-box outputs.
- Conflicting signals are never hidden — if Agent 2 is bearish while Agent 1 is bullish, both are shown with explicit acknowledgement.
- [ASSUMPTION: minimum 3/7 agents must have data available for Agent 5 to generate a recommendation — resolve in architecture phase per Open Item #2]

#### FR-14: Agent 5 Trigger — Hybrid Event + Schedule
Agent 5 is triggered both event-driven (on high-impact signal from Agent 1 or 2) and on a 6-hour scheduled cycle.
**Consequences:**
- Event-driven path: Agent 1/2 detects high-impact signal → Redis pub/sub → Agent 5 wakes within 2 minutes → recommendation generated and push notification fired within 25 minutes of original news.
- Scheduled path: Agent 5 runs every 6 hours for routine portfolio review; output feeds the next Morning Briefing.
- [ASSUMPTION: "high-impact signal" threshold = Source Credibility ≥50 AND relevance score ≥70 AND portfolio overlap]

#### FR-14b: Position-Sizing Guidance
Every actionable Recommendation includes a suggested position-size band relative to portfolio value and risk, not just a direction.
**Consequences:**
- Recommendation card shows a suggested sizing band (e.g., "1–3% of portfolio") with the reasoning (conviction, volatility, existing concentration in the same sector).
- Sizing accounts for current holdings — if Gaurav already holds the stock or is concentrated in its sector, the suggested add is reduced or flagged.
- Sizing is computed by an explicit rule (like the probability score, not LLM-invented). [ASSUMPTION: MVP rule = base band scaled by confidence and capped by a per-position and per-sector concentration limit; exact rule designed in architecture]
- Rationale: most retail loss comes from over-sizing, not bad selection — sizing guidance is higher-leverage than another signal.

#### FR-15: Trade Confirmation with Decision Journal
Gaurav can mark a Recommendation as "Taken" (I executed this trade) or "Declined" (I chose not to act), capturing his reasoning at the moment of decision.
**Consequences:**
- Marking "Taken" opens a form to log entry price, position size, and optional notes.
- At confirmation time the platform captures a **decision rationale snapshot** — a frozen copy of the recommendation's signals, the persona verdicts, and a free-text field for Gaurav's own reasoning ("why I'm taking/declining this"). This snapshot is immutable and timestamped.
- The platform records the outcome (taken/declined) and tracks the stock's actual performance against the recommendation — this data feeds Agent 5's calibration and win-rate calculations.
- "Declined" trades are also tracked — the platform learns which recommendation types Gaurav tends to ignore, and the rationale snapshot lets him later review whether declining was right.
- The rationale snapshot is the seed for the Phase 2 Trade Journal (F22) and auto post-mortems (F28b).

---

### F5. Morning Briefing & Push Notifications
**Phase:** MVP
**Description:** The most frequently used feature. Every morning at 8am the platform generates an AI-written daily briefing aggregating overnight agent outputs: portfolio changes, upcoming market events, pending Recommendations, and top 3 priority actions. Delivered as a push notification to iPhone/iPad; tapping it opens Argus directly to the briefing card. All platform alerts use a 4-tier urgency system. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-16: Morning Briefing (8am daily)
The platform generates and delivers a Morning Briefing via PWA push notification at 8am daily.
**Consequences:**
- Briefing always includes: overnight portfolio P&L, portfolio health score change, upcoming events in next 7 days, any pending Recommendations, and 3 priority actions.
- Briefing card is pinned to the top of the dashboard until Gaurav dismisses it.
- Content is generated by Gemma 2 27B locally — no API cost. [ASSUMPTION]
- On days when agents ran in Degraded Mode overnight, briefing includes a stale-data notice showing what was missed.

#### FR-17: Push Notifications (PWA)
The platform delivers push notifications to Gaurav's iPhone and iPad via the Web Push API.
**Consequences:**
- Push requires PWA installed on iPhone/iPad home screen (one-time setup).
- Notification taps deep-link directly to the relevant section in Argus (briefing, alert, recommendation).
- Notifications work on cellular when away from home via Tailscale.

#### FR-18: Alert Urgency Tiers
All platform alerts are categorized into 4 urgency tiers with distinct delivery channels.
**Consequences:**
- 🔴 Critical (portfolio down ≥10%, breaking news on holding, Black Swan trigger): immediate push notification requiring acknowledgement.
- 🟡 Important (Agent 5 Recommendation, portfolio down 5–9.9%): push notification within 5 minutes of trigger.
- 🟢 Normal (Agent 6 config suggestion, agent self-improvement proposal): included in next Morning Briefing.
- ⚪ Info (weekly performance summary, backup status): weekly digest.

---

### F6. Alert Fatigue Management
**Phase:** MVP
**Description:** 7 agents running continuously could generate hundreds of alerts per day. Without filtering, every signal becomes noise and Gaurav stops paying attention. Alert Fatigue Management is the gate that evaluates every potential notification against confidence + portfolio impact before firing. Silence is a feature — the platform earns trust by only alerting when it genuinely matters. Realizes UJ-2.

**Functional Requirements:**

#### FR-19: Notification Gate
Before any push notification fires, it must pass a combined scoring gate: confidence threshold AND portfolio impact threshold AND relevance filter.
**Consequences:**
- [ASSUMPTION: default thresholds — confidence ≥55%, portfolio impact ≥2% to portfolio value, or Urgency = Critical which bypasses thresholds]
- Suppressed alerts are logged and visible in the Alert Log on the System Operations Dashboard (F9).
- Gaurav can tune thresholds in Settings; defaults are conservative (few alerts, high quality).

#### FR-20: Alert Deduplication
If the same signal triggers multiple agents simultaneously, the platform collapses them into a single notification.
**Consequences:**
- Duplicate detection window: 30 minutes. If the same ticker with the same directional signal appears from multiple agents within 30 minutes, only the highest-confidence version fires.
- Deduplication count is visible in the Alert Log.

---

### F7. Economic Calendar Intelligence — Agent 7
**Phase:** MVP
**Description:** Agent 7 runs daily, tracking upcoming economic events that directly affect Agent 5's recommendation timing: earnings dates for portfolio + watchlist stocks, Fed meetings, CPI/jobs/GDP releases, ex-dividend dates, and lock-up expiries. Without this, Agent 5 is making timing calls without knowing NVDA reports earnings in 3 days. Agent 7 is a critical input to every Morning Briefing. Realizes UJ-1.

**Functional Requirements:**

#### FR-21: Economic Events Calendar
Agent 7 maintains a live calendar of upcoming events affecting Gaurav's holdings and the broader market.
**Consequences:**
- Calendar includes: earnings dates (all portfolio positions), earnings surprise history, Fed meeting dates, CPI/jobs/GDP release dates, ex-dividend dates, and lock-up expiry dates for relevant holdings.
- Data from Finnhub earnings calendar (free) and Federal Reserve RSS feeds (free) — no new API accounts required.
- Calendar is visible on the Earnings & Events screen (basic UI in MVP; full dashboard in Phase 2 as F19).

#### FR-22: Pre-Event Alerts
The platform alerts Gaurav to important upcoming events at appropriate lead times.
**Consequences:**
- Earnings date: alert 3 days before and 1 day before for portfolio positions.
- Fed meeting / CPI: alert 2 days before.
- Ex-dividend date: alert 5 days before (so Gaurav can buy before if desired).
- Lock-up expiry: alert 7 days before.
- All event alerts are delivered in Morning Briefing (🟢 tier) unless within 24 hours (🟡 tier).

#### FR-23: Agent 5 Calendar Integration & Pre-Earnings Quiet Period
Agent 5 receives Agent 7's calendar data before generating any Recommendation and adjusts behavior accordingly, including a hard quiet period around earnings.
**Consequences:**
- **Pre-earnings quiet period (hard rule):** Agent 5 does NOT issue a new directional Recommendation on a stock within [ASSUMPTION: 2 trading days] of its scheduled earnings — price action around earnings is dominated by an unknowable binary outcome, not by the signals Argus tracks. Instead it surfaces an informational "earnings ahead — outcome unpredictable" card.
- If earnings for a stock are within 5 days (but outside the quiet period), Agent 5 notes the earnings date and includes it in the Probability Forecast Card under the bear scenario.
- If a Fed meeting is within 48 hours, Agent 5 adds a timing caveat to any macro-sensitive Recommendation.
- The quiet period is visible and explained on the affected stock's card so Gaurav understands *why* there is no recommendation.

---

### F8. Agent Performance Dashboard
**Phase:** MVP
**Description:** Gaurav needs to know whether the agents are contributing value — not just whether they're running. The Agent Performance Dashboard shows per-agent metrics: status, run history, data volumes, recommendation accuracy (Agent 5 only), and contribution scores. If Agent 5 is consistently wrong, it's visible here before Gaurav loses money following it. Realizes UJ-3.

**Functional Requirements:**

#### FR-24: Per-Agent Status Panel
The dashboard shows live status for all 7 agents: Running / Idle / Analyzing / Error.
**Consequences:**
- Status updates in real time via WebSocket — no page refresh.
- Each agent shows: last run time, next scheduled run, run duration (last run), data items collected (last run), and last error message if any.
- Status dots animate (pulse) when running; solid when idle; red when in error state.

#### FR-25: Agent 5 Recommendation Accuracy Tracking
The dashboard tracks Agent 5's recommendation performance against confirmed trades.
**Consequences:**
- Win rate (%), total recommendations issued, recommendations taken vs. declined, average gain on taken recommendations, average gain on declined recommendations (opportunity cost visibility).
- Performance shown over: All time, Last 30 days, Last 10 recommendations.
- Current Graduation State displayed prominently (Shadow / Probation / Active / Frozen).

#### FR-26: Agent Contribution Attribution
Each Agent 5 recommendation logs the data source weights showing which upstream agents contributed.
**Consequences:**
- Attribution logged as: "Agent 1: 40%, Agent 4: 35%, Agent 2: 25%" per recommendation.
- Per-agent contribution scores are visible in the dashboard — underperforming agents are identifiable.
- Attribution data is the input for Agent 6's self-improvement analysis (Phase 2).

#### FR-26b: Calibration Tracking
The dashboard tracks how well Agent 5's stated probabilities match reality — its *calibration* — not just its win rate.
**Consequences:**
- The platform bins resolved recommendations by stated probability (e.g., all "60–70% bull" calls) and shows the actual hit rate within each bin (a reliability diagram). A well-calibrated engine's "65%" calls come true ~65% of the time.
- Calibration is presented alongside win rate, with a plain-language explanation of why calibration is the more trustworthy measure of judgment than a small-sample win rate.
- Calibration becomes meaningful only after a sample accumulates; until then the dashboard shows "insufficient data — N of [target] resolved" rather than a misleading early number. [ASSUMPTION: target ≈ 50 resolved recommendations before calibration is treated as informative]
- **Honesty guardrail:** because short-horizon win rate at small N is statistical noise, the dashboard explicitly labels win-rate figures below the sample threshold as "not yet statistically meaningful." This protects Gaurav from over-trusting a lucky streak (or abandoning Agent 5 over an unlucky one).
**Notes:** This FR is why FR-12 insists probabilities be model-derived rather than LLM-generated — only a model-derived number can be calibrated.

---

### F9. System Operations Dashboard
**Phase:** MVP (basic); extended metrics in Phase 2
**Description:** A dedicated real-time view into platform health, hardware resources, API budget, and data freshness. The three most important numbers always visible: RAM usage, SSD usage, and monthly spend. This is the "platform heartbeat" screen — Gaurav should be able to confirm in 10 seconds that everything is healthy. Realizes UJ-3.

**Functional Requirements:**

#### FR-27: Hardware Resource Monitor
The dashboard displays live Mac Mini resource usage.
**Consequences:**
- RAM: total usage bar vs. 28GB, plus per-component breakdown (Gemma 4 26B MoE, small model, Spring Boot, databases, OS).
- SSD: total usage vs. 256GB, breakdown by component (PostgreSQL, MongoDB, Redis, Vector DB, Ollama models), plus projected days until full at current growth rate.
- CPU and Neural Engine (M3) utilization.
- [ASSUMPTION: data from Spring Boot Actuator + system metrics endpoint; Grafana optional overlay in Phase 2]

#### FR-28: API Budget Tracker
The dashboard displays live API cost tracking.
**Consequences:**
- Monthly spend to date in USD and CAD (at live FX rate).
- Spend by agent and by model (Claude Haiku vs. local).
- Daily burn rate and projected month-end cost.
- Budget remaining (of $100 CAD) with visual progress bar.
- Visual thresholds: green <70%, amber 70–80%, red 80–95%, flashing red >95%.

#### FR-29: Data Freshness & Backup Status
The dashboard shows when each data source last updated and when the last backup completed.
**Consequences:**
- Stale data alert if any source has not updated within its expected window (Agent 1: >10 min, Agent 7: >25 hours).
- Last successful backup time and backup size.
- Alert if external SSD is disconnected or last backup was >1 hour ago.

---

### F10. Conversational AI — Ask AI
**Phase:** MVP
**Description:** Every Recommendation card, analysis report, and the main portfolio dashboard has an "Ask AI" button. The conversational AI answers questions grounded in Gaurav's specific portfolio and the exact signals that drove the analysis — not generic financial advice. It has full context: portfolio holdings, all 7 agent outputs, investor profile, historical recommendations, and real-time market data. Powered by Gemma 2 27B locally (free). Realizes UJ-2.

**Functional Requirements:**

#### FR-30: Recommendation Chat Mode
From any Recommendation card, Gaurav can open a context-aware chat and ask questions about that specific recommendation.
**Consequences:**
- Chat has full context: the recommendation's Probability Forecast Card, Multi-Signal Diagnostic Report, all agent outputs at time of recommendation, and current portfolio state.
- Response time: ≤15 seconds for Gemma 2 27B local inference. [ASSUMPTION: within M3 performance envelope at 27B quantized]
- Session persists until Gaurav dismisses the chat panel.

#### FR-31: Portfolio Chat Mode
From the main dashboard, Gaurav can open a freeform portfolio chat.
**Consequences:**
- Portfolio Chat has context: all holdings, portfolio health score breakdown, upcoming calendar events, most recent recommendations, and investor profile.
- Typical questions answered: "What should I watch before the Fed meeting?", "Am I on track to reach my goal?", "Which of my holdings concerns you most?"

#### FR-32: Ask AI Escalation Path
For complex multi-step reasoning that exceeds Gemma 2 27B's capability, the platform escalates to Claude Haiku API.
**Consequences:**
- Escalation is automatic (model decides) or can be user-triggered via "Get deeper analysis" button.
- Each escalation costs ~$0.001–0.005 and is tracked in the API budget panel.
- [ASSUMPTION: escalation happens for <10% of conversations — validate in Phase 1 monitoring]

---

### F11. AI Investment Personas
**Phase:** MVP (4 personas); Phase 2 adds 2 more
**Description:** Every Recommendation can be analyzed through 4 investor persona lenses. Same data, completely different philosophies — forces multi-angle thinking. Confidence bias is the biggest risk in investing; the Devil's Advocate persona exists specifically to argue against every recommendation, every time. All personas run on local Gemma 2 27B — zero additional API cost.

**Functional Requirements:**

#### FR-33: 4 MVP Personas
The platform provides 4 AI personas available on every Recommendation: Warren Buffett, Devil's Advocate, Peter Lynch, and Canadian Investor.
**Consequences:**
- "Get Perspectives" button on every Recommendation card shows all 4 personas with a one-line verdict each: ✅ Buy / ⚠️ Cautious / ❌ Risky / 🔄 Neutral.
- Consensus summary line: e.g., "2 Buy · 1 Cautious · 1 Risky."
- Each persona is available in Ask AI conversational mode — Gaurav can ask the Buffett persona direct questions about a recommendation.
- All persona responses generated by Gemma 2 27B locally — zero API cost.

#### FR-34: Canadian Investor Persona
The Canadian Investor persona applies a specific lens: CAD/USD impact on US holdings, TFSA/RRSP tax implications, withholding tax on US dividends, TSX alternatives.
**Consequences:**
- Persona responses for US-listed stocks always include CAD-equivalent numbers and TFSA/RRSP eligibility.
- Persona is unique to Argus — no comparable persona exists elsewhere.

**Notes:** Phase 2 adds Cathie Wood (disruptive innovation) and Ray Dalio (global macro) personas.

---

### F12. Security & Privacy Controls
**Phase:** MVP
**Description:** Argus contains Gaurav's entire financial picture — holdings, portfolio value, trading decisions. Multi-layer security prevents unauthorized access on unattended devices. The Tailscale layer means the platform is never exposed to the public internet; security controls at the app layer are the last line of defense. Realizes UJ-3.

**Functional Requirements:**

#### FR-35: Authentication (PIN + Biometric)
Gaurav authenticates with a 4–6 digit PIN or Face ID / Touch ID on iOS.
**Consequences:**
- PIN is required on first launch and after session timeout.
- Face ID / Touch ID (where available) can be used in place of PIN after initial PIN setup.
- Session timeout is user-configurable: 1 min, 5 min, 15 min (default), 30 min, 1 hour, 4 hours, Never.

#### FR-36: Tap-to-Reveal Privacy Mode
All portfolio values are hidden by default — visible as "••••••" until tapped.
**Consequences:**
- Applies to: total portfolio value, individual position values, P&L figures, Health Score numerical values.
- Reveal state persists within a session; resets on lock.

#### FR-37: Panic Mode
A single tap (or shake gesture on iPhone) blanks the screen to a neutral loading screen instantly.
**Consequences:**
- Activated by: long-press on any blank area, or shake gesture (configurable in Settings).
- Returns to normal with PIN or biometric re-authentication.

#### FR-38: Failed Attempt Lockout
Escalating lockouts on authentication failures.
**Consequences:**
- 3 failed attempts: 30-second lockout.
- 5 failed attempts: 10-minute lockout + push notification sent to secondary device.
- 10 failed attempts: full lock, requires Gaurav to unlock via another Tailscale-connected device.

#### FR-39: Remote Session Kill
Gaurav can terminate any active Argus session from any other device on the Tailscale network.
**Consequences:**
- Available in Settings → Active Sessions.
- Terminates session within 5 seconds of trigger.

---

### F13. Backup & Recovery System
**Phase:** MVP
**Description:** The Mac Mini SSD is a single point of failure. An external Samsung T7 1TB SSD provides automated incremental backup with clear RPO targets. Gaurav can rebuild the entire platform with acceptable data loss. Realized after Chaos Engineering Scenario 1.

**Functional Requirements:**

#### FR-40: Automated Incremental Backup
The platform automatically backs up critical data to the external SSD.
**Consequences:**
- Critical data (portfolio positions, trade journal, investor profile, confirmed recommendations): backup every 15 minutes.
- PostgreSQL full dump: every 6 hours.
- MongoDB (news archive, reports): daily at 2am.
- Redis cache: [ASSUMPTION: not backed up — Redis is a cache layer and can be rebuilt from primary stores]
- If external SSD is disconnected: immediate 🔴 Critical push notification to iPhone.

#### FR-41: Backup Status Visibility
Backup status is always visible in the System Operations Dashboard.
**Consequences:**
- Last successful backup time, backup size, SSD health status.
- Backup failures trigger an immediate 🟡 Important notification.

#### FR-42: Recovery Documentation
[ASSUMPTION: a Recovery Runbook is maintained as a markdown file at the project root, updated at every major architecture change — covers full rebuild steps, expected data loss, and estimated recovery time (~4 hours for full rebuild)]

---

### F14. Remote Access (Tailscale)
**Phase:** MVP — Day 1 requirement
**Description:** Gaurav uses the platform from home, work, and travel via iPhone/iPad. Without remote access, the platform is useless when away from the home network. Tailscale provides a private encrypted mesh VPN — the platform is accessible globally but never exposed to the public internet. Free for personal use, takes <30 minutes to set up.

**Functional Requirements:**

#### FR-43: Tailscale Network Setup
Argus is accessible from any of Gaurav's Tailscale-enrolled devices from anywhere globally.
**Consequences:**
- Mac Mini (server), iPhone, and iPad are all Tailscale nodes.
- The platform binds to a stable Tailscale hostname (e.g., `argus.tail<id>.ts.net`).
- Platform is never bound to a public IP address or public domain.
- Setup is documented in the project README and completed before first agent run.

#### FR-44: Degraded Mode (Internet Outage)
When the Mac Mini loses external internet connectivity, Argus automatically switches to Degraded Mode.
**Consequences:**
- Agents 1, 2, 3, 4, 7 automatically pause (all require internet data sources).
- Agent 5 continues running on cached data with a Stale Data Warning on all Recommendations.
- Agent 6 continues governance and data lifecycle tasks locally.
- Portfolio displays last-known prices with a prominent stale data timestamp.
- The local Gemma model continues answering Ask AI questions (no internet required).
- When internet returns: auto catch-up cycle runs; briefing card notes what was missed during outage.
- Tailscale note: Tailscale is a separate tunnel and operates independently of internet availability for the agents — Gaurav can still access the dashboard; only agent data collection pauses.

---

### F15. Budget Protection & Cost Governor
**Phase:** MVP
**Description:** $100 CAD/month is a hard budget constraint. One busy news week could exhaust the entire month's Claude Haiku budget without this system. The Cost Governor is a subsystem within Agent 6 (MVP version runs as a standalone service; Agent 6 full governance is Phase 2). Notification-first: Gaurav gets to decide at 80% spend; auto-switch is the fallback, not the default. Realized after Chaos Engineering Scenario 5.

**Functional Requirements:**

#### FR-45: Budget Alerts
The platform sends budget notifications at defined thresholds.
**Consequences:**
- 70% of monthly budget consumed: 🟢 Normal alert in next Morning Briefing ("Budget awareness: $52.50 CAD spent of $100 budget this month").
- 80% of monthly budget consumed: 🟡 Important push notification with response window. Options presented: Auto-switch to local Gemma / Keep Claude Haiku / Pause Agent 5 cloud calls. [ASSUMPTION: response window = 30 min during market hours, 2 hours outside market hours]
- If no response within window: platform auto-switches Agent 5's final output to the latest available local Gemma model (resolved at runtime — not hardcoded to a version number).
- 95% consumed: all Claude Haiku API calls automatically paused; platform operates 100% locally until next billing cycle.

#### FR-46: Real-Time Budget Tracking
Every Claude Haiku API call is tracked and attributed.
**Consequences:**
- Spend is visible in the System Operations Dashboard (FR-28) in real time.
- Per-agent and per-model cost breakdown is always available.
- Budget counter resets on first day of each calendar month.

---

### F16–F29. Phase 2 Features (Intelligence Layer)
**Phase:** Phase 2 — estimated 2–3 months after MVP launch

The following features are fully described in the brainstorming source document and will receive full FR expansion in a PRD update pass before Phase 2 architecture begins:

| ID | Feature | Key Capability |
|----|---------|---------------|
| F16 | Social Media Intelligence — Agent 2 | StockTwits + Reddit sentiment; every 2–5 min cycle; Llama 3.2 3B |
| F17 | Financial Reports & Insider Intelligence — Agent 4 | SEC EDGAR Form 4 filings; quarterly reports; earnings analysis; Gemma 2 27B |
| F18 | Watchlist & Stock Discovery | Track pre-buy candidates; entry price alerts; AI discovery matches investor profile |
| F19 | Earnings & Economic Calendar Dashboard | Full UI for Agent 7 data; pre/post earnings analysis; economic event impact modeling |
| F20 | Dividend Tracking & Income Calendar | Ex-date alerts; annual income projection; dividend safety score |
| F21 | Stock Screener | Natural language screening ("profitable Canadian small-cap tech"); saved screeners |
| F22 | Trade Journal & Performance Tracker | Log every trade; Agent 5 vs. own-decision comparison; feedback loop for Agent 5; built on the FR-15 decision-rationale snapshots |
| F23 | Portfolio Risk Analysis | Concentration risk; correlation matrix; drawdown alerts; sector exposure |
| F28b | Auto Post-Mortems | When a recommendation or taken trade resolves as a loss, auto-generate a "why this was wrong" retrospective comparing the decision-rationale snapshot to what actually happened; feeds Agent 6 self-improvement and Gaurav's learning |
| F24 | IPO Intelligence & Tracking | IPO calendar; S-1 filing analysis via Gemma 2 27B; lock-up expiry alerts |
| F25 | Goal Tracker — Waze-style Routing | Set financial target + timeline; continuous on-track monitoring; rerouting alerts |
| F26 | Investor Profile Learning — Netflix-style | Learns from behavior patterns; adapts recommendation style; "Watching Period" tracker |
| F27 | Sunday Discover Weekly — Spotify-style | 5 researched stocks matching investor DNA; "Not Interested" teaches preferences |
| F28 | AI Governance & Self-Improvement — Agent 6 | Data lifecycle management; agent config optimization; model router; improvement proposals |
| F29 | Expanded AI Personas | Cathie Wood (disruptive innovation); Ray Dalio (global macro) |

---

### F30–F43. Phase 3 Features (Strategy & Mastery Layer)
**Phase:** Phase 3 — estimated 3–4 months after Phase 2 launch

| ID | Feature | Key Capability |
|----|---------|---------------|
| F30 | Strategy & Backtesting Engine — Agent 5 Full | QuantConnect integration; strategy generation; historical validation. **Must be designed against look-ahead bias** — point-in-time data only, no information that wasn't available at the simulated decision time; out-of-sample/walk-forward validation required, since look-ahead bias is the #1 cause of backtests that pass and then fail live |
| F31 | Internet Intelligence — Agent 3 | Web intelligence; emerging trend detection; 15-min cycle |
| F32 | Portfolio Simulator / Paper Trading | Paper-trade Agent 5 recommendations; historical what-if analysis |
| F33 | Sector & Industry Heatmap | Visual sector rotation; institutional money flow; portfolio exposure vs. market |
| F34 | Insider Trading & Unusual Activity Dashboard | SEC Form 4 visualization; unusual options activity; short interest changes |
| F35 | Analyst Ratings Aggregator | Wall Street consensus; Agent 5 vs. analyst comparison; upgrade/downgrade tracking |
| F36 | Stock Comparison Tool | Side-by-side financials; ETF overlap detection |
| F37 | Benchmark Comparison | S&P 500, TSX Composite overlay; performance attribution |
| F38 | CAD/FX Impact Dashboard | Live CAD/USD; US holdings value in CAD; currency impact on total return; hedging suggestions |
| F39 | News Archive & Intelligent Search | Searchable MongoDB archive; NLP search across historical news; sentiment timeline |
| F40 | Scenario / Stress Testing | Historical crisis simulation; CAD/USD scenarios; interest rate impact |
| F41 | Year-End Tax Summary | Realized gains/losses; TFSA/RRSP tracker; tax loss harvesting; accountant export |
| F42 | Brokerage API Integration (optional) | Questrade / IBKR / Wealthsimple API; auto-sync; replaces PDF upload |
| F43 | Portfolio Peer Intelligence — Amazon-style | Patterns from investors with similar profiles via 13F filings; weekly intelligence feed |

---

## 5. Non-Goals (Explicit)

- **Automated trading** — Argus never places, modifies, or cancels actual trades. Recommendations only. All execution is manual by Gaurav.
- **Multi-user platform** — No accounts, no sharing, no tenancy beyond Gaurav. Not being built to scale to other users.
- **Real-time Level 2 order flow or sub-second execution** — Out of budget and out of scope for personal investing style.
- **Financial advice** — Platform is an intelligence tool for Gaurav's personal use. Not a licensed financial adviser and must not be represented as one.
- **App Store distribution** — Delivered via PWA only. No App Store submission, no Apple Developer account.
- **Native desktop app** — Web-only for desktop; PWA for mobile. No Electron, no Tauri.
- **Public internet exposure** — Platform is never accessible over the public internet. Tailscale only.
- **Twitter/X integration** — API costs $100 USD/month alone; replaced by StockTwits + Reddit (both free).

---

## 6. MVP Scope

### 6.1 In Scope (MVP — Phase 1)

Based on the Phase 4 Solution Matrix, the following 24 components ship in MVP:

**Foundation:**
- GitHub private repo setup and local dev environment
- Docker Compose local orchestration (Mac Mini M3)
- Tailscale remote access — Day 1
- External SSD automated backup system

**Core Platform:**
- Portfolio upload via PDF + real-time price tracking (FR-1 through FR-5)
- CAD adjusted cost base with purchase-time FX (FR-1b) + corporate-actions handling (FR-1c)
- Portfolio Health Score (FR-6, FR-7)
- Morning Briefing at 8am via PWA push notification (FR-16, FR-17, FR-18)
- Alert Fatigue Management (FR-19, FR-20)

**Agents (MVP subset):**
- Agent 1: News monitoring, source credibility engine, Stranger Danger Protocol (FR-8, FR-9, FR-10)
- Agent 7: Economic calendar with pre-event alerts + pre-earnings quiet period (FR-21, FR-22, FR-23)
- Agent 5: Graduation system, model-derived Probability Forecast Cards, Multi-Signal Diagnostic Report, position-sizing guidance, trade confirmation with decision journal (FR-11 through FR-15, incl. FR-14b)
- Calibration tracking + honest small-sample labeling on the Agent Performance Dashboard (FR-24 through FR-26b)

**Intelligence:**
- Conversational AI — Ask AI on all surfaces (FR-30, FR-31, FR-32)
- 4 AI Personas: Buffett, Devil's Advocate, Lynch, Canadian Investor (FR-33, FR-34)

**Safety Systems:**
- Black Swan Warning System (integrated into FR-12)
- Budget Protection + Cost Governor (FR-45, FR-46)
- Degraded Mode / internet outage handling (FR-44)
- Multi-layer security: PIN + Face ID + Tap-to-reveal + Panic Mode (FR-35 through FR-39)

**Operations:**
- System Operations Dashboard: hardware, budget, data freshness (FR-27, FR-28, FR-29)
- Agent Performance Dashboard: status, accuracy, attribution (FR-24, FR-25, FR-26)

### 6.2 Out of Scope for MVP

- Agent 2 (Social Media) — Phase 2. Deferred: requires separate StockTwits/Reddit rate-limit management; Agent 1 covers news first.
- Agent 3 (Internet Intelligence) — Phase 3. Deferred: complex scraping, lower ROI than Agents 1/4.
- Agent 4 (Financial Reports + SEC Form 4) — Phase 2. Deferred: complex document parsing; Gemma 2 27B needed, high build effort.
- Agent 6 (Full Governance) — Phase 2. MVP includes Cost Governor only as standalone service.
- All Phase 2 and Phase 3 features listed in §4 F16–F43.
- Backtesting via QuantConnect — Phase 3. Agent 5 operates on signal convergence in MVP.
- Benchmark comparison overlay on portfolio chart — Phase 3.
- Brokerage API integration — Phase 3 optional. PDF upload is the MVP and default.
- Canadian tax features (TFSA/RRSP room tracker, tax loss harvesting) — Phase 3.
- [NOTE FOR PM]: Goal Tracker is load-bearing for the Waze design principle and long-term stickiness — flag for early Phase 2 scheduling even if the build estimate looks high.

---

## 7. Success Metrics

**Primary**

> **Framing:** Argus is a decision-support and discipline tool (see §1). Success metrics therefore measure *use, discipline, judgment quality (calibration), and cost control* — deliberately NOT market-beating returns, which the platform does not claim to deliver and which short-sample data cannot honestly attribute to skill.

- **SM-1: Daily Active Use** — Gaurav opens Argus at least once per day after MVP launch, sustained over 30 days. Validates FR-16 (Morning Briefing), FR-6 (Health Score). *Target: ≥25 of first 30 days post-launch.*
- **SM-2: Agent 5 Calibration** — Once a meaningful sample of recommendations has resolved, Agent 5's stated probabilities track reality. Validates FR-11, FR-12, FR-26b. *Target: within the calibration sample, the gap between stated probability and actual hit rate per bin is ≤15 percentage points. Until the sample threshold is reached, this metric reports "insufficient data" rather than a number.*
- **SM-3: Push Notification Quality** — ≤2 push notifications per day on average (alert fatigue management working). Validates FR-19. *Target: average ≤2 notifications/day across first 30 days.*
- **SM-4: Operating Cost** — Monthly API spend stays within $100 CAD. Validates FR-45, FR-46. *Target: every month ≤$100 CAD.*

**Secondary**

- **SM-5: Morning Briefing Open Rate** — Gaurav opens ≥80% of Morning Briefing push notifications. Validates FR-16.
- **SM-6: Ask AI Usage** — Gaurav uses Ask AI on ≥50% of recommendations in the first 30 days. Validates FR-30, FR-31.
- **SM-7: Recommendation Confirmation Rate** — Gaurav marks ≥30% of recommendations as Taken or Declined *with a rationale snapshot* (engagement + discipline signal — even Declined is useful data). Validates FR-15.
- **SM-8: Backup Health** — Zero backup gaps >1 hour on critical data over first 30 days. Validates FR-40.
- **SM-9: Bias-Confrontation** — Gaurav views persona perspectives (especially Devil's Advocate) on ≥50% of recommendations he acts on. Validates FR-33. *This measures whether the discipline mechanism is actually used.*

**Counter-Metrics (do not optimize)**

- **SM-C1: Recommendation Volume** — Do not optimize for Agent 5 generating more recommendations. Higher volume without better calibration degrades trust. Counterbalances SM-7.
- **SM-C2: Push Notification Volume** — Do not optimize for notification open rate by sending more notifications. Counterbalances SM-5.
- **SM-C3: Trading Frequency** — Do not optimize for Gaurav taking more trades. The platform's job is better decisions, not more activity; a quiet week with no good setups is a success, not a failure. Counterbalances SM-1 and SM-7.

*Success at MVP: The platform is running, I check it every morning, Agent 5 is honestly calibrated (or honestly flagged as not-yet-proven), costs are under budget, I confront the bear case before acting, and I haven't made an impulsive, biased decision the platform could have talked me out of.*

---

## 8. Open Questions

1. **Agent Orchestration Technology** — Java Spring Boot (virtual threads + Redis pub/sub) vs. Python LangChain/CrewAI for agent orchestration. Decision needed before architecture begins. *Owner: Architecture phase.*
2. **Minimum Agent Quorum for Recommendations** — How many agents must have data available before Agent 5 is allowed to generate a recommendation? Current assumption: 3/7. *Owner: Architecture phase, informed by testing.*
3. **Vector DB Selection** — pgvector (embedded in PostgreSQL, zero ops overhead) vs. Chroma vs. Weaviate. *Owner: Architecture phase.*
4. **Agent 5 Performance Benchmark** — What benchmark does Agent 5 compare its win rate against? S&P 500 total return, TSX Composite, or a fixed absolute threshold? *Owner: Gaurav decision before Phase 2.*
5. **Agent 5 Frozen Mode Trigger Threshold** — Confirmed 3 consecutive losses in Active Mode OR win rate below 40% over rolling 10 trades. Validate these thresholds feel right before implementation. *Owner: Gaurav.*
6. **Local Model RAM Contention** — Gemma 4 26B MoE (~15–16GB) still cannot stay resident alongside the always-on small model and macOS with comfortable headroom. When Agent 4 (overnight analysis), Agent 5, Conversational AI, and Personas all want the workhorse model, how is loading/eviction and queuing handled? **Still the top architecture decision** — the MoE choice eases the memory and speed pressure but does not eliminate the need for a policy. *Owner: Architecture phase.*
7. **Trading Strategies & Prompts Repo** — Should agent prompts and trading strategy logic live in a separate private repo from platform code? *Owner: Architecture phase.*
8. **Agent 5 Exit Criteria** — Who defines when a recommendation is "closed" for win/loss tracking — Agent 5 based on price target + time horizon, or Gaurav explicitly? *Owner: Gaurav before Phase 1 Agent 5 build.*
9. **Conversational AI Latency Target** — With Gemma 4 26B MoE (~4B active params/token) the 15s target is now plausible on the M3, where a 27B-dense model would not have been. Confirm against live benchmarks; if still short, fall back to token streaming (so it *feels* responsive) or a smaller chat model. *Owner: Architecture phase.*

---

## 9. Assumptions Index

All `[ASSUMPTION]` tags from the document:

| # | Location | Assumption |
|---|----------|-----------|
| A-1 | FR-2 | USD/CAD FX from Finnhub on hourly refresh (NOT exchangerate-api.com — free tier insufficient) |
| A-2 | FR-6 | Health Score recalculated at 6am after Agent 4 and 7 overnight runs complete |
| A-3 | FR-11 | Paper trade "win" = stock moves in predicted direction within stated time horizon |
| A-4 | FR-11 | Frozen Mode triggers on: 3 consecutive losses in Active Mode OR win rate below 40% over rolling 10 trades |
| A-5 | FR-13 | Minimum 3/7 agents must have data available for Agent 5 to generate a recommendation |
| A-6 | FR-14 | "High-impact signal" threshold = Source Credibility ≥50 AND relevance score ≥70 AND portfolio overlap |
| A-7 | FR-16 | Morning Briefing text generated by the local Gemma 4 model (no API cost) |
| A-8 | FR-19 | Default notification gate thresholds: confidence ≥55%, portfolio impact ≥2%, or Urgency = Critical (bypasses) |
| A-9 | FR-30 | Gemma 4 26B MoE (~4B active params/token) completes Ask AI responses in ≤15s on the M3 — plausible where a 27B-dense model was not; confirm with benchmarks |
| A-10 | FR-32 | Ask AI escalation to Claude Haiku happens in <10% of conversations |
| A-11 | FR-40 | Redis cache is not backed up to external SSD — treated as ephemeral, rebuilt from primary stores |
| A-12 | FR-42 | A Recovery Runbook markdown document is maintained at project root and updated at major architecture changes |
| A-13 | FR-45 | Budget notification response window: 30 min during market hours, 2 hours outside market hours |
| A-14 | F1 NFR | PDF parse accuracy target: ≥95% of standard brokerage statement fields without manual correction |
| A-15 | §4 F30 | Agent 5 does not use QuantConnect backtesting in MVP — deferred to Phase 3 |
| A-16 | FR-1b | ACB maintained as weighted-average cost across lots (Canadian non-registered convention), not FIFO/lot-specific |
| A-17 | FR-1c | Corporate-actions data sourced from Finnhub; verified against SEC filings via Agent 4 in Phase 2 |
| A-18 | FR-12 | MVP probability/confidence computed by a transparent rule/weight scoring engine over agent signals; LLM writes narrative only, never the number |
| A-19 | FR-14b | Position-size band = base band scaled by confidence, capped by per-position and per-sector concentration limits |
| A-20 | FR-23 | Pre-earnings quiet period = no new directional recommendation within 2 trading days of scheduled earnings |
| A-21 | FR-26b | Calibration treated as informative only after ≈50 resolved recommendations; win rate below threshold labeled "not statistically meaningful" |
| A-22 | §1 / Glossary | Claude Haiku prompts carry sanitized analysis context, never raw portfolio positions (privacy guardrail) |

---

## 10. Technology Constraints
*Confirmed in brainstorming and encoded as architecture guardrails. These are not options — they are decisions made.*

### Hardware
- **Deployment target:** Mac Mini M3, 28GB unified memory, 256GB SSD
- **RAM envelope:** ~28GB total. The deep-analysis workhorse is **Gemma 4 26B MoE** at ~15–16GB (4-bit), down from ~20GB for a 27B-dense model — this frees ~4–5GB. Even so, after macOS (~5–7GB) the budget is tight: the large model plus the always-on small model plus all services is still near the ceiling. A model loading/eviction policy remains REQUIRED, not optional (open question #6) — the MoE choice eases it, it does not remove it.
- **SSD envelope:** 256GB — projected growth rate must be monitored; Agent 6 manages data lifecycle to prevent overflow

### Backend
*(Versions verified current as of June 2026.)*
- **Language + Runtime:** **Java 25 LTS** (released Sept 2025 — the current LTS; Java 26 exists but is a non-LTS 6-month release, so 25 LTS is the right base for a long-lived project). Project Loom virtual threads are now mature and core to the concurrent agent runtime.
- **Framework:** **Spring Boot 4.0.x** (latest stable; 4.1.0 released June 2026). NOTE: this is a major-version jump from the originally-specced 3.x — Spring Boot 4 requires Spring Framework 7 and a Java 17+ baseline (satisfied by Java 25). Starting greenfield on 4.0 avoids a near-term major migration. [ASSUMPTION: no library in the stack is incompatible with Spring Boot 4 / Spring Framework 7 — verify key deps at scaffold time]
- **Real-time:** Spring WebSocket (dashboard live updates)
- **Messaging:** Redis pub/sub as event bus between agents

### Frontend
*(Versions verified current as of June 2026.)*
- **Framework:** **Next.js 16** (16.2.x LTS stable; 16 GA Oct 2025 — Turbopack is the default bundler, Node.js 20+ required). Single codebase for web + PWA.
- **Runtime:** **Node.js 22 LTS** (or current LTS ≥20, required by Next.js 16)
- **Styling:** **TailwindCSS v4** + shadcn/ui (latest)
- **Charts:** TradingView Lightweight Charts — latest stable (free, professional-grade)
- **Mobile delivery:** PWA (Progressive Web App) — not native iOS
- **Push notifications:** Web Push API via PWA service worker

### AI Layer
*(Models verified current as of June 2026. **Gemma 4 — released April 2026 — materially improves this project's hardest constraints; see note below.**)*
- **Local model runtime:** Ollama — latest stable (free, native Apple Silicon / Metal support)
- **Deep-analysis workhorse (Agents 4, 5, 6, Conversational AI, Personas):** **Gemma 4 26B MoE** via Ollama. This is the recommended default over the 31B dense variant — see the MoE note below.
- **High-frequency agents (Agents 1, 2, 3):** **Gemma 4 E4B** (effective-4B) or **Llama 3.2 3B** — small, fast, fits the always-on lightweight slot. [ASSUMPTION: pick whichever benchmarks better on sentiment/relevance tagging at scaffold time; both are ~3–4GB class]
- **Agent 5 final output:** Current Claude Haiku API model at build time (~$5–10 USD/month) — falls back to the local Gemma 4 model at 80% budget consumption
- **Model versioning:** Platform never hardcodes model version strings. Agent 6 evaluates new releases in Shadow Mode for 7 days before recommending an upgrade. `ollama pull` is the only upgrade mechanism — no code changes needed.

> **Why Gemma 4 26B MoE is the right pick for this hardware (important):**
> Gemma 4's 26B variant uses a Mixture-of-Experts design — it activates only ~4B parameters per token while delivering near-frontier (27B-class) quality. Two direct consequences for Argus:
> 1. **Lower memory pressure** — at typical 4-bit quantization the 26B MoE resident footprint (~15–16GB) is smaller than a 27B-dense model (~19–20GB), freeing ~4–5GB against the 28GB ceiling. This eases (does not eliminate) the model-contention problem in open question #6.
> 2. **Much faster inference** — only ~4B active params per token means responses generate several times faster than a 27B-dense model on the M3. This is what makes the Conversational AI latency target (open question #9) realistic, and shrinks Agent 5's processing window.
>
> The 31B dense variant remains an option if maximum reasoning quality is ever needed for batch (overnight) analysis, but for an interactive, RAM-constrained, single-box deployment, **26B MoE is strictly the better default.** Architecture phase confirms final choice against live benchmarks.

### Databases
*(Versions verified current as of June 2026.)*
- **PostgreSQL 18** (18.4 stable) — structured data: portfolio positions, prices, recommendations, trade journal, ACB lots
- **MongoDB 8.3** — documents: news articles, financial reports, social posts, analysis outputs
- **Redis 8** (8.8 stable) — caching + real-time pub/sub agent event bus. NOTE: Redis 8 is licensed AGPLv3 — fine for private personal use; no redistribution. [ASSUMPTION: confirm license posture is acceptable; Valkey is a permissively-licensed drop-in fallback if ever needed]
- **Vector DB** — AI semantic search and embedding storage. **Leaning pgvector** (rides inside PostgreSQL 18 — zero extra service in the 28GB budget) vs. Chroma — open question #3; use latest stable of whichever is chosen.

### Infrastructure
- **Orchestration:** Docker Compose v2 — latest stable (databases + Spring Boot + Next.js only)
- **Ollama runs NATIVELY on macOS host — never containerized.** Docker Desktop on Mac has no GPU/Metal passthrough; a containerized model falls back to CPU and becomes unusably slow. Spring Boot reaches Ollama over `host.docker.internal` (or runs on host too).
- **Version control:** GitHub Free private repository
- **Remote access:** Tailscale — latest stable (free personal plan)
- **Observability:** Spring Boot Actuator for system metrics; Grafana optional in Phase 2

### External APIs (all free tier unless noted)

| Service | Agents | Purpose |
|---------|--------|---------|
| Finnhub | 1, 4, 7 | News, prices (WebSocket), earnings calendar, IPO data |
| Alpha Vantage | 4 | Fundamental data |
| GDELT Project | 1 | Global news, no auth required |
| SEC EDGAR | 4 | Filings, Form 4 insider data, S-1s |
| StockTwits | 2 | Social sentiment |
| Reddit API | 2 | Social sentiment |
| QuantConnect | 5 | Backtesting (Phase 3) |
| Claude Haiku API | 5 | Final recommendation output (~$10 USD/month) |
| Finnhub (FX + indices) | System, 7 | USD/CAD rate (hourly); VIX and index levels for Black Swan triggers |

### Budget Constraint
- **Hard ceiling:** $100 CAD/month operating cost
- **Estimated actual cost:** $10–15 USD/month at current API usage
- **Budget buffer:** ~$35–65 USD/month buffer within $75 USD equivalent

---

## 11. Agent Architecture
*Confirmed in brainstorming. This section is PRD-level context for the architecture phase — mechanism and orchestration details belong in the architecture document.*

### 7-Agent Roster

| Agent | Role | Run Frequency | Processing Time | Models Used |
|-------|------|--------------|----------------|-------------|
| Agent 1 | News Intelligence | Every 5 min | 3–9 min | Gemma 4 E4B / Llama 3.2 3B (small) |
| Agent 2 | Social Media Intelligence | Every 2–5 min | 2–5 min | Gemma 4 E4B / Llama 3.2 3B (small) |
| Agent 3 | Internet Intelligence | Every 15 min | 6–12 min | Gemma 4 E4B / Llama 3.2 3B (small) |
| Agent 4 | Financial Reports & Insider Data | Daily 2am | 1–4 hours | Gemma 4 26B MoE |
| Agent 5 | Strategy & Recommendations | Event + 6hr schedule | 5–10 min | Gemma 4 26B MoE + Claude Haiku |
| Agent 6 | Governance & Self-Improvement | Daily | 10–30 min | Gemma 4 26B MoE |
| Agent 7 | Economic Calendar | Daily | 3–4 min | Gemma 4 26B MoE |

*Processing-time estimates predate the Gemma 4 MoE choice and are conservative — MoE inference is materially faster than the 27B-dense assumption they were based on; revise during architecture once benchmarked.*

### Agent Communication Model
- Agents communicate via **Redis pub/sub** (event bus) — not direct API calls
- Agent 5 subscribes to high-impact signal events from Agents 1 and 2
- Agent 6 subscribes to all agent completion events for governance
- All agent outputs are stored in the shared data layer (PostgreSQL + MongoDB) — not passed peer-to-peer

### Data Flow — Breaking News to iPhone Notification
```
News published                              T+0
Agent 1 detects (next 5-min cycle)         T+0 to T+5 min
Agent 1 processes + stores                  T+5 to T+12 min
Redis pub/sub → Agent 5 wakes              T+12 to T+14 min
Agent 5 generates recommendation           T+14 to T+22 min
Web Push API → iPhone notification         T+22 to T+24 min
Total end-to-end:                          ~20–25 minutes
```

### MVP Agent Deployment
Phase 1 deploys Agents 1, 5, and 7 only. Agents 2 and 4 (Phase 2), Agent 3 and Agent 6 full (Phase 3). Agent 6 Cost Governor subsystem ships in MVP as standalone service.

### Cold Start Timeline (When Agents Begin Delivering Value)
- Day 1: Prices, basic news, no recommendations (Agent 5 in Shadow Mode)
- Week 1: First paper recommendations internally logged (low confidence, building baselines)
- Week 2–3: Confidence scores become meaningful; sentiment baselines established
- Month 1: Agent 5 may reach Probation Mode if paper win rate ≥70% over 20 trades
- Month 3: Deep intelligence mature, full pipeline delivering consistent value
- Month 6: Pattern recognition genuinely valuable; Agent 6 suggesting improvements

---

## 12. UI Design Constraints
*Confirmed in brainstorming.*

### Color System
| Token | Hex | Use |
|-------|-----|-----|
| Background | `#0A0A0F` | Main canvas |
| Surface | `#13131A` | Cards and panels |
| Border | `#1E1E2E` | Element separation |
| Accent | `#00D4FF` | Buttons, highlights, active states |
| Gains | `#00FF88` | Positive numbers, bullish |
| Losses | `#FF3B5C` | Negative numbers, bearish |
| Warning | `#FFB800` | Alerts, cautions |
| Text primary | `#E8E8F0` | Main content |
| Text secondary | `#6B7280` | Labels, timestamps |

### Typography
- Primary: Inter (free) — headlines Bold 24–32px, large numbers Bold 28–48px tabular, body Regular 14–16px, labels Medium 11–12px uppercase
- Monospace: JetBrains Mono — agent logs, technical data

### Layout
- **Desktop web:** Left sidebar nav (fixed) + top bar (Health Score + total value always visible) + main content + right panel (live alerts + recommendations) + bottom strip (agent status + RAM/SSD/cost)
- **Mobile (iPhone/iPad PWA):** Bottom tab bar (5 tabs: Home, Portfolio, Intelligence, Agents, Profile) + full-width stacked cards

### Responsive Breakpoints
- Mobile (iPhone): 375–390px
- Tablet (iPad): 768–1024px
- Desktop: 1280px+

---

## 13. Cross-Cutting NFRs

### Performance
- Portfolio value update latency: ≤1 second from Finnhub WebSocket price tick
- Push notification delivery: ≤2 seconds once triggered by agent
- Ask AI response time: ≤15 seconds (Gemma 2 27B local)
- Dashboard WebSocket updates: ≤500ms from event to screen

### Reliability
- Platform must auto-recover from agent failures without user intervention (retry + alerting)
- Degraded Mode activates automatically on internet loss — no manual intervention required
- Backup system must never have a gap >1 hour for critical data during normal operation

### Security
- Platform is never exposed on the public internet — Tailscale mesh only
- No financial data leaves the home network except via Claude Haiku API (recommendation text, not portfolio data) [ASSUMPTION: Claude Haiku prompts contain analysis context but never raw portfolio positions — validate in architecture]
- All local database storage is unencrypted at rest (FileVault on Mac Mini is the encryption layer) [ASSUMPTION]

### Observability
- All agent runs logged with structured JSON: agent ID, trigger type, items processed, errors, duration, model used, cost
- All Agent 5 recommendations logged with full attribution for audit
- Budget spend logged per API call, per agent, per day

### Constraints
- Must operate within 28GB unified RAM (hard constraint — M3 hardware)
- Must operate within $100 CAD/month API budget (hard constraint)
- Platform runs on macOS only in v1 (no Linux port, no cloud deployment in scope)

---

*PRD Status: Draft — pending Reviewer Gate pass before architecture begins.*
*Next step: `bmad-create-architecture`*
