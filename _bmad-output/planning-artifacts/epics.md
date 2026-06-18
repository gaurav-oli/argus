---
stepsCompleted: [1, 2, 3, 4]
status: complete
completedAt: '2026-06-17'
inputDocuments:
  - "_bmad-output/planning-artifacts/prds/prd-ProjectX-2026-06-15/prd.md"
  - "_bmad-output/planning-artifacts/architecture.md"
project_name: "Argus"
---

# Argus - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Argus, decomposing the requirements from the PRD (incl. embedded UX spec §12) and the Architecture into implementable stories. Greenfield project — Epic 1 begins with scaffold + the on-hardware validation spike.

## Requirements Inventory

### Functional Requirements (MVP — Phase 1)

**Portfolio (F1/F2)**
- FR-1: Portfolio upload via PDF (parse holdings: ticker, shares, cost basis, date)
- FR-1b: Adjusted Cost Base (ACB) with purchase-time FX (CAD), weighted-average per security
- FR-1c: Corporate-actions handling (splits, reverse splits, ticker changes, mergers)
- FR-2: Real-time portfolio value (Finnhub WS, ≤1s tick, CAD/USD)
- FR-3: Holdings table (sortable, color-coded, mobile expand)
- FR-4: Portfolio chart (TradingView Lightweight Charts, time ranges)
- FR-5: Manual position add/edit/remove (logged)
- FR-6: Portfolio Health Score (0–100, daily recompute)
- FR-7: Health Score breakdown with per-point deductions + fixes + 30-day trend

**News Intelligence — Agent 1 (F3)**
- FR-8: News ingestion ≤5-min cycle (Finnhub/GDELT/RSS), stored with sentiment/relevance
- FR-9: Source Credibility Engine (0–100 tiers, auto-block <10, weekly discovery)
- FR-10: Stranger Danger Protocol for unknown stocks (6/7 threshold, pump-and-dump score)

**Recommendations — Agent 5 (F4)**
- FR-11: Agent 5 Graduation System (Shadow→Probation→Active→Frozen)
- FR-12: Probability Forecast Card — **probabilities MODEL-derived (rule/weight engine), LLM writes narrative only**
- FR-13: Multi-Signal Diagnostic Report (per-agent attribution, conflicts shown)
- FR-14: Hybrid trigger (event-driven via Redis Streams + 6h scheduled)
- FR-14b: Position-sizing guidance (rule-based band, concentration-aware)
- FR-15: Trade confirmation with decision-rationale snapshot (immutable)

**Briefing & Notifications (F5/F6)**
- FR-16: Morning Briefing 8am daily (local model, pinned card)
- FR-17: Push notifications (PWA Web Push, deep-link, works on cellular via Tailscale)
- FR-18: Alert urgency tiers (Critical/Important/Normal/Info)
- FR-19: Notification gate (confidence + portfolio-impact thresholds)
- FR-20: Alert deduplication (30-min window)

**Economic Calendar — Agent 7 (F7)**
- FR-21: Economic/earnings events calendar (Finnhub + Fed RSS)
- FR-22: Pre-event alerts (earnings, Fed/CPI, ex-div, lock-up lead times)
- FR-23: Agent 5 calendar integration + **pre-earnings quiet period** (no directional rec within 2 trading days of earnings)

**Agent Performance Dashboard (F8)**
- FR-24: Per-agent status panel (live, WebSocket)
- FR-25: Agent 5 recommendation accuracy tracking (win rate, taken/declined)
- FR-26: Agent contribution attribution (data-source weights)
- FR-26b: Calibration tracking (reliability diagram; small-sample "not statistically meaningful" labeling)

**System Operations Dashboard (F9)**
- FR-27: Hardware resource monitor (RAM/SSD/CPU/Neural Engine, growth projection)
- FR-28: API budget tracker (spend by agent/model, projection, thresholds)
- FR-29: Data freshness & backup status (stale alerts, SSD disconnect alert)

**Conversational AI — Ask AI (F10)**
- FR-30: Recommendation chat mode (full context, ≤15s target)
- FR-31: Portfolio chat mode (holdings + health + calendar context)
- FR-32: Ask AI escalation to Claude Haiku (tracked)

**Personas (F11)**
- FR-33: 4 MVP personas (Buffett, Devil's Advocate, Lynch, Canadian) via local model
- FR-34: Canadian Investor persona (CAD/USD, TFSA/RRSP, withholding tax)

**Security (F12)**
- FR-35: Auth — PIN + Face/Touch ID, configurable session timeout
- FR-36: Tap-to-reveal privacy mode
- FR-37: Panic Mode (tap/shake → blank)
- FR-38: Failed-attempt escalating lockout
- FR-39: Remote session kill (over Tailscale)

**Backup & Recovery (F13)**
- FR-40: Automated incremental backup to external SSD (15-min critical / 6h pg_dump)
- FR-41: Backup status visibility
- FR-42: Recovery runbook documentation

**Remote Access & Resilience (F14)**
- FR-43: Tailscale network setup (Day 1, never public internet)
- FR-44: Degraded Mode (internet outage: pause net agents, continue local, auto catch-up)

**Budget Protection (F15)**
- FR-45: Budget alerts (70/80/95% thresholds, notification-first auto-switch)
- FR-46: Real-time budget tracking (per call/agent/model)

*Phase 2/3 FRs (F16–F43 in PRD) are out of scope for these MVP epics; captured for future epic passes.*

### NonFunctional Requirements

- NFR-1 (Perf): price update ≤1s from Finnhub WS; push ≤2s; Ask AI ≤15s (pending GAP-2 validation); dashboard WS ≤500ms
- NFR-2 (Reliability): agents auto-recover (retry+alert); Degraded Mode activates automatically; no critical-data backup gap >1h
- NFR-3 (Security): never on public internet (Tailscale only); Claude Haiku prompts carry sanitized context, never raw positions; FileVault at rest
- NFR-4 (Observability): structured JSON logs (agent, trigger, items, errors, duration, model, cost); full Agent 5 attribution; per-call budget logging
- NFR-5 (Constraints): operate within 28GB RAM (Mini) and $100 CAD/month; macOS only in v1

### Additional Requirements (from Architecture)

- AR-1: **Greenfield scaffold** — backend via Spring Initializr (Maven, Java 25, Spring Boot 4.0.x, deps: web/websocket/data-jpa/data-redis/actuator/validation/postgresql); frontend via `create-next-app@latest` (Next 16, TS, Tailwind v4, Turbopack). → **Epic 1, Story 1.**
- AR-2: **GAP-1/2 RAM + latency validation spike** on the Mac Mini (load Gemma 4 26B MoE, measure RAM + Ask-AI latency) → **Epic 1, before feature work.**
- AR-3: Docker Compose data layer (PostgreSQL 18 + pgvector 0.8.2 + Redis 8); Flyway baseline schema; Ollama runs natively on host.
- AR-4: **Model Gateway** (Spring AI 2.0 ChatClient wrapper; tiered keep-alive; serialized big-model access; pre-warm; Haiku fallback; dev/prod profiles) — foundational, blocks all LLM features.
- AR-5: **Agent runtime** (virtual threads, @Scheduled, Redis Streams producer/consumer, base Agent, lifecycle) + Degraded Mode platform-mode coordinator (GAP-3).
- AR-6: Finnhub resilience (Resilience4j rate-limiting + documented fallback) — GAP-4.
- AR-7: REST + STOMP WebSocket gateway; RFC 9457 Problem Details; springdoc-openapi.
- AR-8: Server-side Redis sessions; Argon2/BCrypt PIN; iOS passkey/WebAuthn.
- AR-9: Probability scoring engine design (model-derived rule/weight) — GAP-6, prerequisite for FR-12 & FR-26b.
- AR-10: Tailscale provisioning + external SSD backup job (@Scheduled).

### UX Design Requirements (from PRD §12)

- UX-DR1: Dark & Premium theme — implement color tokens (#0A0A0F bg, #13131A surface, #00D4FF accent, #00FF88 gains, #FF3B5C losses, #FFB800 warning, text #E8E8F0/#6B7280)
- UX-DR2: Typography — Inter (UI) + JetBrains Mono (logs/technical), defined scale, tabular figures for numbers
- UX-DR3: Desktop "Dashboard First" layout — left sidebar, top bar (Health Score + value), main chart+holdings, right alerts/recs panel, bottom agent status strip
- UX-DR4: Mobile/iPad PWA layout — 5-tab bottom bar (Home, Portfolio, Intelligence, Agents, Profile), full-width stacked cards, pull-to-refresh
- UX-DR5: Portfolio Health Score widget (number + bar + color thresholds + trend + tap-to-breakdown)
- UX-DR6: Recommendation Card (weather-style: bull/bear probability bar, target, horizon, 7-agent signal dots, Ask AI, Watch/Dismiss)
- UX-DR7: Agent Status Bar (7 agents, colored status dots, RAM/SSD/cost always visible)
- UX-DR8: Morning Briefing card (8am, pinned, collapsible)
- UX-DR9: Premium details — animated number transitions, glow on active, loading skeletons, ⌘K command palette, haptics on iOS, frosted-glass cards
- UX-DR10: Responsive breakpoints (mobile 375–390, tablet 768–1024, desktop 1280+); shadcn/ui component set

### FR Coverage Map

Every requirement maps to exactly one epic (no orphans).

- **Epic 1 (Foundation):** AR-1, AR-2, AR-3, AR-4, AR-5, AR-7, AR-10, FR-43, GAP-1, GAP-2, UX-DR1, UX-DR2, UX-DR3, UX-DR4, UX-DR9, UX-DR10
- **Epic 2 (Security):** FR-35, FR-36, FR-37, FR-38, FR-39, AR-8
- **Epic 3 (Portfolio):** FR-1, FR-1b, FR-1c, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, UX-DR5
- **Epic 4 (News/Agent 1):** FR-8, FR-9, FR-10, AR-6
- **Epic 5 (Calendar/Agent 7):** FR-21, FR-22, FR-23
- **Epic 6 (Recommendations/Agent 5):** FR-11, FR-12, FR-13, FR-14, FR-14b, FR-15, AR-9, UX-DR6
- **Epic 7 (Ask AI & Personas):** FR-30, FR-31, FR-32, FR-33, FR-34
- **Epic 8 (Briefing & Notifications):** FR-16, FR-17, FR-18, FR-19, FR-20, UX-DR8
- **Epic 9 (Ops Dashboards):** FR-24, FR-25, FR-26, FR-26b, FR-27, FR-28, FR-29, UX-DR7
- **Epic 10 (Resilience, Backup & Budget):** FR-40, FR-41, FR-42, FR-44, FR-45, FR-46, GAP-3

## Epic List

### Epic 1: Foundation & Walking Skeleton
Stand up a deployed, Tailscale-accessible, empty Argus on the Mac Mini with the model stack proven on real hardware. By the end, the monorepo is scaffolded, the data layer runs, the Model Gateway + agent runtime skeletons exist, REST+WebSocket work end-to-end, the dark-theme dashboard shell renders, and the **RAM/latency validation spike (GAP-1/2) has passed** — confirming the architecture holds before any feature is built.
**Reqs covered:** AR-1, AR-2, AR-3, AR-4, AR-5, AR-7, AR-10, FR-43, GAP-1, GAP-2, UX-DR1–4, UX-DR9, UX-DR10

### Epic 2: Security & Access
Securely access Argus: PIN + Face/Touch ID login with configurable timeout, tap-to-reveal privacy, panic mode, escalating failed-attempt lockout, and remote session kill — all over Tailscale, never the public internet.
**Reqs covered:** FR-35, FR-36, FR-37, FR-38, FR-39, AR-8

### Epic 3: Portfolio Monitoring
Upload a portfolio (PDF), then see live value and P&L with correct Canadian ACB (purchase-time FX) and corporate-actions handling, plus the always-visible Portfolio Health Score with explained deductions.
**Reqs covered:** FR-1, FR-1b, FR-1c, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, UX-DR5

### Epic 4: News Intelligence (Agent 1)
Agent 1 continuously ingests news, scores source credibility (auto-blocking bad sources), and applies the unknown-stock protection protocol — feeding trustworthy signals into the platform.
**Reqs covered:** FR-8, FR-9, FR-10, AR-6

### Epic 5: Economic Calendar (Agent 7)
Agent 7 maintains the earnings/Fed/economic-events calendar, fires pre-event alerts at the right lead times, and enforces the pre-earnings quiet period that gates recommendations.
**Reqs covered:** FR-21, FR-22, FR-23

### Epic 6: Recommendations & Trust (Agent 5)
Agent 5 produces weather-style Probability Forecast Cards (probabilities computed by an auditable scoring engine, not the LLM) with diagnostic attribution, position-sizing guidance, the Shadow→Probation→Active→Frozen graduation system, and trade confirmation with decision-rationale snapshots.
**Reqs covered:** FR-11, FR-12, FR-13, FR-14, FR-14b, FR-15, AR-9, UX-DR6

### Epic 7: Conversational AI & Personas
Ask AI about any recommendation or the whole portfolio (grounded in real context, local-model first with Haiku escalation), and view 4 investor-persona perspectives including the Canadian Investor lens.
**Reqs covered:** FR-30, FR-31, FR-32, FR-33, FR-34

### Epic 8: Briefing, Notifications & Alert Discipline
Receive the 8am Morning Briefing and tiered push notifications on iPhone/iPad, with alert-fatigue gating and deduplication so only signal — not noise — reaches you.
**Reqs covered:** FR-16, FR-17, FR-18, FR-19, FR-20, UX-DR8

### Epic 9: Operations Dashboards
See whether the platform is healthy and whether the agents earn their keep: per-agent status/accuracy/attribution, calibration tracking, and the system-ops view (RAM/SSD/CPU, API budget, data freshness).
**Reqs covered:** FR-24, FR-25, FR-26, FR-26b, FR-27, FR-28, FR-29, UX-DR7

### Epic 10: Resilience, Backup & Budget
Protect the running system: automated external-SSD backups with status visibility and a recovery runbook, Degraded Mode for internet outages (platform-mode coordinator), and the 70/80/95% budget protection with notification-first auto-switch to local models.
**Reqs covered:** FR-40, FR-41, FR-42, FR-44, FR-45, FR-46, GAP-3

---

## Epic 1: Foundation & Walking Skeleton

Stand up a deployed, Tailscale-accessible, empty Argus on the Mac Mini with the model stack proven on hardware before any feature work.

### Story 1.1: Scaffold the monorepo
As the builder, I want the backend and frontend scaffolded in one repo, so that I have a runnable baseline to build on.
**Acceptance Criteria:**
**Given** an empty repo, **When** I run the documented Spring Initializr (Maven, Java 25, Boot 4.0.x) and `create-next-app@latest` (Next 16, TS, Tailwind v4) commands into `backend/` and `frontend/`, **Then** both apps start locally with a default page/health endpoint **And** the feature-package skeleton (`com.argus.{config,common,model,agent,portfolio,...}`) exists.

### Story 1.2: Stand up the data layer
As the builder, I want Postgres 18 (+pgvector) and Redis 8 running via Docker Compose with a Flyway baseline, so that the app has persistence.
**Acceptance Criteria:**
**Given** Docker Compose up, **When** the backend starts, **Then** it connects to Postgres and Redis **And** Flyway applies a baseline migration **And** the pgvector extension is available.

### Story 1.3: RAM + latency validation spike on the Mac Mini (GAP-1/2)
As the builder, I want to measure Gemma 4 26B MoE memory and latency on the Mini, so that I confirm the architecture holds before building features.
**Acceptance Criteria:**
**Given** Ollama running natively on the Mini with the small model resident, **When** the 26B MoE is loaded under worst-case (DBs + JVM + a generation request), **Then** total resident RAM and headroom are recorded **And** Ask-AI-style first-token + full-response latency is measured **And** if the ≤28GB margin or ≤15s target fails, the model/keep-alive policy is revised and re-tested before proceeding.

### Story 1.4: Model Gateway skeleton
As the builder, I want a Model Gateway wrapping Spring AI 2.0 with dev/prod profiles, so that all LLM calls route through one component.
**Acceptance Criteria:**
**Given** profile `dev`, **When** a test prompt is sent, **Then** it routes to a small/mock model; **Given** profile `prod`, **Then** it routes to Gemma 4 26B MoE via Ollama **And** big-model access is serialized (concurrency=1) with configurable keep-alive **And** a Haiku fallback path exists (stub acceptable here).

### Story 1.5: Agent runtime + Redis Streams plumbing
As the builder, I want a base Agent abstraction with scheduling and Redis Streams pub/consume, so that real agents can be added consistently.
**Acceptance Criteria:**
**Given** the runtime, **When** a demo agent publishes an event to a Stream, **Then** a consumer group receives and acks it **And** agents run on virtual threads via `@Scheduled` **And** the standard event envelope (`eventId,type,occurredAt,version,payload`) is enforced.

### Story 1.6: REST + WebSocket round-trip
As the builder, I want REST and STOMP WebSocket wired end-to-end, so that the frontend can call APIs and receive live pushes.
**Acceptance Criteria:**
**Given** the frontend, **When** it calls a sample `/api` endpoint, **Then** it receives a typed JSON response (errors as RFC 9457) **And** when the backend publishes to a STOMP topic, the frontend receives it live.

### Story 1.7: Dark-theme dashboard shell
As the user, I want the Argus shell (theme, layout, navigation), so that the app looks and navigates like the spec.
**Acceptance Criteria:**
**Given** the app, **When** I open it on desktop, **Then** I see the dark-premium tokens, sidebar + top bar + panels (UX-DR3); **When** on iPhone/iPad, **Then** the 5-tab bottom-nav PWA layout (UX-DR4) renders responsively **And** loading skeletons + Inter/JetBrains Mono typography are applied.

### Story 1.8: Tailscale access + deploy-to-Mini runbook
As the user, I want Argus reachable from my devices over Tailscale and a repeatable deploy, so that I can run it on the Mini from anywhere.
**Acceptance Criteria:**
**Given** Tailscale on Mini + iPhone, **When** I open the Tailscale hostname, **Then** the shell loads with no public-internet exposure **And** a documented `git pull` + `docker compose up` + native Ollama deploy procedure exists and succeeds on the Mini.

---

## Epic 2: Security & Access

### Story 2.1: PIN setup and login
As the user, I want to set and enter a PIN, so that only I can open Argus.
**Acceptance Criteria:**
**Given** first launch, **When** I set a 4–6 digit PIN, **Then** it's stored as an Argon2/BCrypt hash and a Redis-backed session starts on correct entry **And** an incorrect PIN is rejected.

### Story 2.2: Biometric unlock
As the user, I want Face/Touch ID unlock, so that I get in quickly.
**Acceptance Criteria:**
**Given** a PIN is set, **When** I enable passkey/WebAuthn, **Then** subsequent unlocks accept biometrics **And** fall back to PIN if biometrics fail.

### Story 2.3: Configurable session timeout
As the user, I want to choose the auto-lock timeout, so that I balance convenience and safety.
**Acceptance Criteria:**
**Given** settings, **When** I pick a timeout (1m…4h…Never, default 15m), **Then** the session locks after that idle period requiring re-auth.

### Story 2.4: Tap-to-reveal privacy
As the user, I want values hidden until tapped, so that bystanders can't see my finances.
**Acceptance Criteria:**
**Given** any screen, **When** it loads, **Then** values show as `••••••` until tapped **And** reveal resets on lock.

### Story 2.5: Panic mode
As the user, I want an instant blank screen, so that I can hide the app fast.
**Acceptance Criteria:**
**Given** a configured gesture (long-press/shake), **When** triggered, **Then** the screen blanks to a neutral state immediately **And** returns only after re-auth.

### Story 2.6: Failed-attempt lockout
As the user, I want escalating lockouts, so that brute force is deterred.
**Acceptance Criteria:**
**Given** failed PIN attempts, **When** 3/5/10 failures occur, **Then** lockouts escalate (30s / 10m + alert / full lock requiring another device).

### Story 2.7: Remote session kill
As the user, I want to end a session from another device, so that I can secure a lost device.
**Acceptance Criteria:**
**Given** an active session, **When** I trigger session-kill from another Tailscale device, **Then** the target session terminates within 5s.

---

## Epic 3: Portfolio Monitoring

### Story 3.1: Portfolio PDF upload
As the user, I want to upload a brokerage PDF, so that my holdings load without manual entry.
**Acceptance Criteria:**
**Given** a statement PDF, **When** I upload it, **Then** holdings (ticker, shares, cost basis, date) appear within 60s **And** unparseable fields are flagged for manual entry, not dropped silently.

### Story 3.2: ACB with purchase-time FX
As the Canadian investor, I want CAD cost basis at purchase-time FX, so that P&L and tax are correct.
**Acceptance Criteria:**
**Given** a US holding bought in CAD, **When** cost basis is computed, **Then** CAD ACB uses the USD/CAD rate at purchase (weighted-average across lots) **And** unknown purchase FX is flagged "FX estimated" until confirmed.

### Story 3.3: Corporate-actions handling
As the user, I want splits/ticker-changes/mergers applied automatically, so that my positions stay correct.
**Acceptance Criteria:**
**Given** a split on a holding, **When** detected, **Then** share count and per-share cost basis adjust so total cost basis is preserved **And** I'm notified; **Given** an ambiguous action, **Then** a 🟡 alert requests manual confirmation rather than corrupting the position.

### Story 3.4: Real-time portfolio value
As the user, I want live value and P&L, so that I see my portfolio update in real time.
**Acceptance Criteria:**
**Given** market hours, **When** a Finnhub WS price tick arrives, **Then** total value and per-position P&L update within 1s with smooth number transitions **And** after-hours prices are labeled.

### Story 3.5: Holdings table
As the user, I want a sortable holdings table, so that I can scan my positions.
**Acceptance Criteria:**
**Given** holdings, **When** I view the table, **Then** I see ticker/name/shares/cost/value/day & total P&L/weight, sortable by any column, color-coded **And** rows expand on mobile.

### Story 3.6: Portfolio chart
As the user, I want a value chart over time ranges, so that I see performance trends.
**Acceptance Criteria:**
**Given** the dashboard, **When** I pick a range (1D…1Y), **Then** a TradingView Lightweight chart renders the portfolio value for that range.

### Story 3.7: Manual position edit
As the user, I want to add/edit/remove positions, so that I can correct or update without re-uploading.
**Acceptance Criteria:**
**Given** holdings, **When** I add/edit/remove a position, **Then** calculations update immediately **And** the change is logged with a timestamp.

### Story 3.8: Health Score calculation
As the user, I want a 0–100 health score, so that I get an at-a-glance portfolio assessment.
**Acceptance Criteria:**
**Given** my holdings, **When** the daily recompute runs, **Then** a 0–100 score is produced from concentration/diversification/risk/agent signals **And** it's always visible in the top bar with color thresholds.

### Story 3.9: Health Score breakdown widget
As the user, I want each deduction explained, so that I know how to improve.
**Acceptance Criteria:**
**Given** a score below 100, **When** I tap it, **Then** every point deduction is listed with a specific reason and suggested fix **And** a 30-day trend is shown.

---

## Epic 4: News Intelligence (Agent 1)

### Story 4.1: News ingestion pipeline
As the user, I want Agent 1 to ingest news on a schedule, so that the platform tracks what's happening.
**Acceptance Criteria:**
**Given** Agent 1 running, **When** a ≤5-min cycle fires (≤15m off-hours), **Then** new articles from Finnhub/GDELT/RSS are stored with source, time, and ticker-relevance tags.

### Story 4.2: Sentiment & relevance tagging
As the user, I want each article scored for sentiment and relevance, so that only pertinent signals matter.
**Acceptance Criteria:**
**Given** an ingested article, **When** the small model processes it, **Then** a sentiment score and relevance-to-holdings tags are persisted.

### Story 4.3: Source Credibility Engine
As the user, I want sources scored and bad ones blocked, so that fake news has little weight.
**Acceptance Criteria:**
**Given** a source, **When** its signals resolve correct/incorrect, **Then** its 0–100 score adjusts (+2/−3), tiered Platinum…Blocked **And** sources below 10 are auto-blocked and I'm notified **And** unknown sources start at 35.

### Story 4.4: Stranger Danger Protocol
As the user, I want extra scrutiny on unknown stocks, so that I'm protected from pump-and-dumps.
**Acceptance Criteria:**
**Given** heavy coverage of a non-held/non-watchlist stock, **When** detected, **Then** an elevated 6/7-agent threshold applies, market-cap/volume/coordinated-posting checks run, and a pump-and-dump risk score is produced.

### Story 4.5: Finnhub resilience (GAP-4)
As the builder, I want rate-limiting and a fallback, so that Finnhub limits don't break ingestion.
**Acceptance Criteria:**
**Given** Finnhub rate limits, **When** they're approached, **Then** Resilience4j throttles/retries with backoff **And** a documented fallback (Alpha Vantage/Yahoo) is wired or stubbed with a clear toggle.

---

## Epic 5: Economic Calendar (Agent 7)

### Story 5.1: Calendar ingestion
As the user, I want a live events calendar, so that I know what's coming.
**Acceptance Criteria:**
**Given** Agent 7's daily run, **When** it executes, **Then** earnings dates (holdings), Fed/CPI/jobs/GDP, ex-dividend, and lock-up dates are stored from Finnhub + Fed RSS.

### Story 5.2: Pre-event alerts
As the user, I want lead-time alerts, so that events don't surprise me.
**Acceptance Criteria:**
**Given** upcoming events, **When** lead times hit (earnings 3d/1d, Fed/CPI 2d, ex-div 5d, lock-up 7d), **Then** an alert is queued (🟢 in briefing, 🟡 within 24h).

### Story 5.3: Agent 5 calendar integration + quiet period
As the user, I want recommendations to respect earnings timing, so that I'm not given false confidence before a binary event.
**Acceptance Criteria:**
**Given** a stock with earnings within 2 trading days, **When** Agent 5 would recommend, **Then** it instead surfaces an "earnings ahead — outcome unpredictable" card; **Given** earnings within 5 days (outside quiet period), **Then** the date is noted in the card's bear scenario.

---

## Epic 6: Recommendations & Trust (Agent 5)

### Story 6.1: Probability scoring engine (GAP-6)
As the builder, I want an auditable scoring engine, so that probabilities are real, not LLM-invented.
**Acceptance Criteria:**
**Given** agent signals for a stock, **When** the engine runs, **Then** it outputs bull/bear probability and a confidence score from explicit weighted inputs **And** every number is traceable to its inputs **And** no probability originates from an LLM.

### Story 6.2: Multi-signal diagnostic report
As the user, I want to see which agents drove a recommendation, so that it's not a black box.
**Acceptance Criteria:**
**Given** a recommendation, **When** I expand the diagnostic, **Then** each agent's bullish/bearish/neutral assessment and weight is shown **And** conflicting signals are displayed, not hidden.

### Story 6.3: Probability Forecast Card UI
As the user, I want a weather-style recommendation card, so that I grasp the outlook at a glance.
**Acceptance Criteria:**
**Given** a recommendation, **When** it renders, **Then** I see direction, bull/bear probability bar, price target, horizon, confidence, 7-agent signal dots, and Watch/Dismiss/Ask AI actions **And** when a Black Swan is active, confidence is capped at 60% with a warning banner.

### Story 6.4: Hybrid trigger
As the user, I want timely and routine recommendations, so that I'm alerted on big moves and reviewed regularly.
**Acceptance Criteria:**
**Given** a high-impact signal on a Stream, **When** consumed, **Then** Agent 5 wakes within 2 min and a card is produced within ~25 min of the source event; **And** a 6-hour scheduled review also runs feeding the briefing.

### Story 6.5: Position-sizing guidance
As the user, I want a suggested position size, so that I don't over-concentrate.
**Acceptance Criteria:**
**Given** a recommendation, **When** shown, **Then** a sizing band (e.g., 1–3%) with reasoning appears, reduced/flagged if I'm already concentrated in that stock/sector, computed by an explicit rule.

### Story 6.6: Graduation state machine
As the user, I want Agent 5 to earn trust, so that I don't follow an unproven engine.
**Acceptance Criteria:**
**Given** Shadow mode, **When** ≥70% over ≥20 paper trades, **Then** it promotes to Probation (UNVALIDATED badge); **And** Active demotes if rolling win rate <50%/10; **And** a serious-failure pattern triggers Frozen (no new recs, self-diagnosis, FROZEN warning on pending).

### Story 6.7: Trade confirmation + rationale snapshot
As the user, I want to log taken/declined with my reasoning, so that the platform learns and I can review later.
**Acceptance Criteria:**
**Given** a recommendation, **When** I mark Taken/Declined, **Then** an immutable timestamped snapshot of signals + persona verdicts + my free-text reasoning is stored **And** actual outcome is tracked against it for calibration/win-rate.

---

## Epic 7: Conversational AI & Personas

### Story 7.1: Recommendation chat
As the user, I want to ask about a recommendation, so that I can interrogate it before acting.
**Acceptance Criteria:**
**Given** a recommendation, **When** I open Ask AI and ask a question, **Then** the answer is grounded in that rec's signals, diagnostic, and my portfolio, via the Model Gateway, within the latency target.

### Story 7.2: Portfolio chat
As the user, I want to ask about my whole portfolio, so that I get holistic answers.
**Acceptance Criteria:**
**Given** the dashboard, **When** I ask a portfolio question, **Then** the answer uses holdings + health breakdown + calendar + recent recs as context.

### Story 7.3: Haiku escalation
As the user, I want complex questions escalated, so that hard reasoning still works.
**Acceptance Criteria:**
**Given** a complex query (auto-detected or via "deeper analysis"), **When** escalation triggers, **Then** the Model Gateway routes to Claude Haiku **And** the cost is recorded.

### Story 7.4: Four personas
As the user, I want persona perspectives, so that I see multiple viewpoints.
**Acceptance Criteria:**
**Given** a recommendation, **When** I tap "Get Perspectives", **Then** Buffett, Devil's Advocate, Lynch, and Canadian personas each return a verdict (✅/⚠️/❌/🔄) with a consensus summary, generated locally.

### Story 7.5: Canadian Investor persona
As the Canadian investor, I want a Canadian lens, so that recommendations reflect my context.
**Acceptance Criteria:**
**Given** a US-listed recommendation, **When** the Canadian persona responds, **Then** it includes CAD-equivalent figures and TFSA/RRSP eligibility/withholding-tax notes.

---

## Epic 8: Briefing, Notifications & Alert Discipline

### Story 8.1: Web Push setup
As the user, I want push notifications on my devices, so that I'm alerted when away.
**Acceptance Criteria:**
**Given** the PWA installed, **When** I enable notifications, **Then** a Web Push subscription registers **And** a test push arrives on iPhone/iPad over Tailscale and deep-links into the app.

### Story 8.2: Alert urgency tiers
As the user, I want alerts prioritized, so that critical ones stand out.
**Acceptance Criteria:**
**Given** an alert, **When** classified, **Then** Critical → immediate push (requires ack), Important → push ≤5m, Normal → next briefing, Info → weekly digest.

### Story 8.3: Alert-fatigue gate
As the user, I want low-value alerts suppressed, so that the platform stays signal-not-noise.
**Acceptance Criteria:**
**Given** a candidate notification, **When** evaluated, **Then** it fires only if it passes confidence + portfolio-impact thresholds (or is Critical) **And** suppressed alerts are logged.

### Story 8.4: Alert deduplication
As the user, I want duplicates collapsed, so that I'm not pinged repeatedly.
**Acceptance Criteria:**
**Given** the same ticker+direction from multiple agents within 30 min, **When** evaluated, **Then** only the highest-confidence one fires and the dedupe count is logged.

### Story 8.5: Morning Briefing
As the user, I want an 8am summary, so that I start the day informed.
**Acceptance Criteria:**
**Given** 8am, **When** the briefing runs, **Then** a locally-generated card (overnight P&L, health change, upcoming events, pending recs, top 3 actions) is delivered via push and pinned **And** if agents ran in Degraded Mode overnight, a stale-data notice is included.

---

## Epic 9: Operations Dashboards

### Story 9.1: Per-agent status panel
As the user, I want live agent status, so that I know the platform is working.
**Acceptance Criteria:**
**Given** the Agents view, **When** open, **Then** all agents show Running/Idle/Analyzing/Error with last/next run, duration, items collected, last error — updating live over WebSocket.

### Story 9.2: Agent 5 accuracy tracking
As the user, I want Agent 5's record, so that I gauge its reliability.
**Acceptance Criteria:**
**Given** resolved recommendations, **When** I view accuracy, **Then** win rate, total issued, taken vs declined, and average gains show over All/30d/last-10 **And** the current Graduation State is prominent.

### Story 9.3: Contribution attribution
As the user, I want per-agent contribution, so that I can spot dead weight.
**Acceptance Criteria:**
**Given** recommendations with logged source weights, **When** I view attribution, **Then** per-agent contribution percentages are shown and underperformers are identifiable.

### Story 9.4: Calibration tracking
As the user, I want calibration, not just win rate, so that I trust the probabilities honestly.
**Acceptance Criteria:**
**Given** resolved recs binned by stated probability, **When** I view calibration, **Then** a reliability diagram shows actual hit-rate per bin **And** below the sample threshold it shows "insufficient data" and labels win rate "not statistically meaningful".

### Story 9.5: Hardware resource monitor
As the user, I want RAM/SSD/CPU visibility, so that I know the Mini isn't stressed.
**Acceptance Criteria:**
**Given** the Ops view, **When** open, **Then** live RAM (vs 28GB, per-component), SSD (vs 256GB, per-store, days-to-full), CPU and Neural Engine load are shown via Actuator/system metrics.

### Story 9.6: API budget tracker
As the user, I want spend visibility, so that I stay within budget.
**Acceptance Criteria:**
**Given** API usage, **When** I view budget, **Then** month-to-date spend (CAD/USD), by agent and model, daily burn, projection, and remaining-of-$100 show with green/amber/red thresholds.

### Story 9.7: Data freshness & backup status
As the user, I want freshness and backup status, so that I trust the data and my safety net.
**Acceptance Criteria:**
**Given** the Ops view, **When** open, **Then** each source's last-update time shows with stale alerts, and last backup time/size with an alert if SSD is disconnected or backup is >1h old.

---

## Epic 10: Resilience, Backup & Budget

### Story 10.1: Automated incremental backup
As the user, I want automatic backups to the external SSD, so that I never lose critical data.
**Acceptance Criteria:**
**Given** the external SSD connected, **When** the schedule runs, **Then** critical data backs up every 15 min and a `pg_dump` every 6h **And** a disconnected SSD raises an immediate 🔴 push.

### Story 10.2: Backup status visibility
As the user, I want backup status surfaced, so that I know my safety net works.
**Acceptance Criteria:**
**Given** backups running, **When** I view status, **Then** last success time, size, and SSD health show **And** a failure raises a 🟡 notification.

### Story 10.3: Recovery runbook
As the builder, I want a recovery procedure, so that I can rebuild after failure.
**Acceptance Criteria:**
**Given** a backup set, **When** I follow the runbook, **Then** the platform restores with documented expected data loss and steps **And** the runbook lives at the project root and is referenced in docs.

### Story 10.4: Degraded Mode coordinator (GAP-3)
As the user, I want graceful behavior during outages, so that the platform stays useful offline.
**Acceptance Criteria:**
**Given** internet loss is detected, **When** Degraded Mode activates, **Then** net-dependent agents pause, Agent 5 runs on cached data with stale warnings, local Ask AI still works, and the dashboard shows last-known prices **And** on reconnect an auto catch-up runs and flags what was missed.

### Story 10.5: Real-time budget tracking
As the builder, I want every paid call tracked, so that governance has accurate data.
**Acceptance Criteria:**
**Given** a Claude Haiku call, **When** it completes, **Then** cost is recorded per call/agent/model and the monthly counter resets on the 1st.

### Story 10.6: Budget alerts + auto-switch
As the user, I want budget protection, so that I never exceed $100 CAD.
**Acceptance Criteria:**
**Given** monthly spend, **When** it hits 70/80/95%, **Then** 70% → 🟢 briefing note, 80% → 🟡 push with options + response window (no response → auto-switch to local Gemma), 95% → all cloud calls paused (100% local) until next cycle.
