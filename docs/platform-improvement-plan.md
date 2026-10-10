# Argus — Platform Improvement Plan

**Canonical handoff doc** for continuing work across Cursor / Claude sessions when tokens run out.  
Replaces `platform-review-2026-10.md` (findings kept below as Appendix A).

| Field | Value |
|---|---|
| Last updated | 2026-10-09 |
| Product intent | Multi-source intelligence → scored buy/sell/hold → **paper trades validate** → feedback/learning → patterns & per-stock strategies → operator builds trust → **only then** consider real trades |
| How to continue | Read **Session checkpoint**, then **Agent protocol**, then **Story board**. Start the first `not-started` story unless the user picks another. |

Related: [`multi-user.md`](multi-user.md) §5 · [`deploy-runbook.md`](deploy-runbook.md) · [`design-terminal-noir.md`](design-terminal-noir.md)

---

## Session checkpoint (read this first)

**Branch:** `feature/platform-improvement-s-a1-websocket-auth` (do **not** merge to `main` until the owner asks).  
**Tip of branch (pushed):** `28ba426` — `docs: add session checkpoint for next-agent handoff after S-B1`  
**Do not start a new feature branch** for the remaining stories — keep committing on this same branch.

### Done on this branch (do not redo)

| ID | Commit | What shipped |
|---|---|---|
| S-A1 | `e7a0ab0` | WS handshake requires signed-in session; block raw `/queue` subscribe |
| S-A2 | `2f054b6` | Session list/revoke scoped to signed-in user |
| S-A3 | `1aeae22` | Admin-gate ops mutators + global settings + Demo Mode |
| S-A4 | `0485611` | Holdings-linked / ticker pushes → `sendToUser`; `/api/push/test` caller-only |
| S-A5 | `a76e0b8` | All Haiku paths via `paidFallback` + Cost Governor (≥95% refuses paid) |
| S-A6 | `89cd746` | Userless sessions fail auth; PDF `%PDF` magic; push unsub ownership |
| S-B1 | `3b43977` | `TrustScoreboard` on Home + Intelligence; accuracy `prior30d` trend |
| S-B2 | `eda43a4` | Trust bar (config + `/api/recommendations/trust-bar`), persistent paper-lab banner, checklist on the scoreboard |
| S-B3 | `5da7bdf` | Per-trade lessons (V87 `trade_lesson`), "what changed" settled from logic review / Agent 13, lessons feed on Agents + ledger + Intelligence |

Phase **A (security)** is complete. Phase **B**: `S-B1`, `S-B2`, `S-B3` done.

### Next story for the next agent

**→ S-B4 — Pattern library consulted before next paper trade** — status `in-progress` (Claude Code, 2026-10-10). Owner asked to work through the remaining stories in order in auto mode.

Follow the **Agent protocol** below: mark `in-progress` in this file first, implement only S-B4 acceptance criteria, mark `done` + Completed note, commit + push on this branch, then **stop and ask** before the next story.

### Owner follow-up

When the remaining stories (or a batch) are done, the owner will return to the original session for a **validation pass** against this plan’s acceptance criteria — keep Completed notes accurate so that review is easy.

---

## Agent protocol (mandatory)

1. **Pick one story** — the next `not-started` (or `in-progress`) item in priority order below, unless the user names a different story id.
2. Set that story’s **Status** to `in-progress` in this file **before** coding.
3. Implement only that story’s acceptance criteria. Prefer small, reviewable diffs. Do not start the next story in the same turn.
4. When acceptance criteria are met:
   - Set **Status** to `done`
   - Add a one-line **Completed** note (date + short what shipped)
   - If the story maps to Appendix A / `multi-user.md` §5, strike or mark that gap fixed there too
5. **Stop and ask the user:**  
   > “Story S-xx is done. Do you want to move on to the next story (S-yy)?”  
   Do **not** start S-yy until they say yes.
6. If blocked, set Status to `blocked`, write why under **Notes**, and ask the user.
7. Keep the **Story board** table in sync whenever any story status changes.

Status values: `not-started` · `in-progress` · `done` · `blocked` · `cancelled`

---

## Product vision (owner)

The platform should:

1. Ingest many sources (macro/micro/geo news, earnings, market & social sentiment, insider, etc.).
2. Run technical / fundamental / candlestick / strategy algorithms and produce **buy / sell / do nothing** with a confidence score.
3. Show that intelligence on **Intelligence** (confidence + rationale).
4. **Automatically paper-trade** recommendations to validate the theory.
5. On every win *and* loss: feedback loop → understand the gap → improve next decisions.
6. Store reusable **patterns**; before the next paper trade, check similar past setups and act accordingly (size, skip, stop, add, etc.).
7. Learn which strategies fit which kinds of stocks; discover/try new strategies in a sandbox before promoting them.
8. Operator watches win rate + calibration until trust is high enough for real money — **no pressure to Take/Decline personally** as the core loop (optional later).

Human Take/Decline remount is **optional / later** (Phase D), not the main path.

---

## Agent fleet guidance (do we need new agents?)

**Short answer:** Not immediately. Finish Phase A security and Phase B learning **by extending existing agents** first. The Mini has a hard RAM/model budget — every new always-on agent competes with Gemma. Add at most **2–3 new agents** later, and only when a job does not cleanly fit Agents 5 / 13 / 15.

### What you already have (keep / deepen)

| # | Agent / subsystem | Role for your vision |
|---|---|---|
| 1 | News Intelligence | Macro/micro/geo headlines, sentiment, stranger danger |
| 2–3 | Social + Internet | Market/social chatter, attention |
| 4 | SEC insider | Form 4 |
| 5 | Recommender + Investor paper book | Score buy/sell/hold + **auto paper-trade** |
| 6 | Cost Governor | Haiku budget |
| 7–8 | Calendar + Macro | Events, quiet periods, macro tagging |
| 9 | On-demand Research | Deep dive on request |
| 10–12 | Chart / Deep / Fundamentals | Technical, multi-stage, valuation |
| 13 | Trade Learner | Lessons / learned rules from paper wins & losses — **extend for S-B3** |
| 14 | Filings Reader | 10-Q/10-K digests |
| 15 | Academic Strategies | Published signals — **extend toward S-B6/B7 sandbox** |
| — | Logic Review + Adaptive Tuning + Calibration | Feedback that changes weights (not a numbered “agent” but core to learning) |

Most of your goal is **wiring and productizing** this fleet (trust UI, pattern consult, sandbox promote/kill), not inventing Agent 16–25 on day one.

### Extend first (no new agent number)

| Capability | Prefer extending | Why |
|---|---|---|
| Post-trade “why win/loss” narrative | **Agent 13 Trade Learner** + Logic Review | Already owns lessons; S-B3 should land here |
| Per-stock strategy fit | **Agent 15** + paper ledger tags | Avoid a parallel scoring brain |
| Regime / “what kind of market” | Existing `MarketRegime` / macro tags | Feed fingerprints for S-B4, don’t spawn a twin |
| Geo-political depth | **Agent 1 + 8** prompts/sources | Same ingestion spine; better tagging beats a new process |
| Candlestick / TA quality | **Agent 10 Chart Reader** | Already feeds recommendations and stops |

### New agents worth adding later (only after S-B1–B4 prove the loop)

| Proposed | Job | When to add | Maps to |
|---|---|---|---|
| **Agent 16 — Pattern Matcher** | Fingerprint setups; before each paper entry, retrieve similar past paper outcomes and recommend skip / size / stop / proceed | After S-B3 exists (needs closed-trade lessons) | S-B4, **S-E1** |
| **Agent 17 — Strategy Scout** | Find candidate strategies from trusted online/academic sources; stage them into **shadow** only; never auto-promote | After trust scoreboard exists; feeds S-B7 | S-B7, **S-E2** |
| **Agent 18 — Earnings Call Reader** *(optional)* | Transcript digests (tone, guidance changes) as an extra signal — distinct from 10-Q/10-K Filings Reader | Only if you care about call audio/transcripts specifically | **S-E3** (optional) |

### Do **not** add as full agents

| Idea | Better form |
|---|---|
| “Trust Governor” / real-money bar | Config + UI service (**S-B2**), not a scheduled LLM agent |
| “Smarter trading ideas” freeform agent | Contaminates probabilities; keep LLM for prose/debate, numbers in Agent 5 |
| Duplicate news scrapers per theme | Source packs + tags inside Agent 1 |
| Human discipline coach agent | Optional S-D1 overlay later |

### Rule of thumb

1. If it **changes scores or paper entries** → Agent 5 / Investor / Pattern Matcher.  
2. If it **writes lessons from closed paper** → Agent 13.  
3. If it **proposes new playbooks** → Strategy Scout → sandbox → Agent 15/5 promote path.  
4. If it’s **UI or a threshold** → not an agent.

---

## Story board (priority order)

Work top → bottom. Do not skip Phase A for Funnel-exposed hosts.

| Order | ID | Story | Phase | Status |
|---|---|---|---|---|
| 1 | S-A1 | Lock WebSocket: auth + block raw portfolio queues | A — Security | done |
| 2 | S-A2 | Scope session list/revoke to the signed-in user | A — Security | done |
| 3 | S-A3 | Admin-gate ops mutators + global settings + Demo Mode | A — Security | done |
| 4 | S-A4 | Route portfolio-derived pushes to `sendToUser` | A — Security | done |
| 5 | S-A5 | Gate all Haiku paths on Cost Governor | A — Security | done |
| 6 | S-A6 | Reject userless sessions; PDF magic bytes; push unsubscribe ownership | A — Security | done |
| 7 | S-B1 | Trust scoreboard front-and-center (wins, Brier, sample size, graduation) | B — Paper trust | done |
| 8 | S-B2 | Paper-validation bar (explicit “not real money until bar clears”) | B — Paper trust | done |
| 9 | S-B3 | Post-trade learning narrative (win and loss) | B — Learning | done |
| 10 | S-B4 | Pattern library consulted before next paper trade | B — Learning | in-progress |
| 11 | S-B5 | Intelligence as active thesis board (confidence + paper P&L + why) | B — Learning | not-started |
| 12 | S-B6 | Per-stock / per-style strategy fit tracking | B — Strategies | not-started |
| 13 | S-B7 | Strategy sandbox: shadow → promote or kill | B — Strategies | not-started |
| 14 | S-C1 | Per-user journal, watchlist, notification prefs | C — Multi-user | not-started |
| 15 | S-C2 | Admin uninvite / disable user / revoke-all sessions | C — Multi-user | not-started |
| 16 | S-C3 | Per-user AI / import soft quotas | C — Multi-user | not-started |
| 17 | S-D1 | Optional: human Agree/Disagree overlay on Intelligence | D — Later | not-started |
| 18 | S-D2 | Align chrome copy with paper-lab positioning | D — Later | not-started |
| 19 | S-D3 | Frontend `error.tsx` + visible refresh failures | D — Later | not-started |
| 20 | S-E1 | New Agent 16 — Pattern Matcher (only if S-B4 needs a dedicated runtime) | E — New agents | not-started |
| 21 | S-E2 | New Agent 17 — Strategy Scout (shadow candidates only) | E — New agents | not-started |
| 22 | S-E3 | Optional Agent 18 — Earnings Call Reader | E — New agents | not-started |

---

## Phase A — Security (Funnel-safe)

### S-A1 — Lock WebSocket: auth + block raw portfolio queues

- **Status:** `done`
- **Priority:** P0
- **Finding:** C2
- **Goal:** Nobody without a valid session+userId can receive live portfolio (or other personal) STOMP messages; raw `/queue/**` subscribe is denied.
- **Acceptance:**
  - Handshake without valid session+userId is rejected (or connects with zero access to personal destinations).
  - STOMP `ChannelInterceptor` (or equivalent) denies client SUBSCRIBE to raw `/queue/**`.
  - Integration test: stranger cannot subscribe to `/queue/portfolio-user{N}` and receive another user’s snapshot.
  - Authenticated user’s `/user/queue/portfolio` still works.
- **Hints:** `SessionPrincipalHandshakeHandler`, `WebSocketConfig`, `LivePushService`, `LivePortfolioService`; Funnel exposes `/ws`.
- **Completed:** 2026-10-09 — `SessionAuthHandshakeInterceptor` + `StompDestinationGuard`; ITs in `StompRoundTripIntegrationTest` (branch `feature/platform-improvement-s-a1-websocket-auth`).
- **Notes:** —

### S-A2 — Scope session list/revoke to the signed-in user

- **Status:** `done`
- **Priority:** P0
- **Finding:** C1
- **Goal:** `GET/DELETE /api/auth/sessions` only affect the caller’s sessions.
- **Acceptance:**
  - List returns only sessions for `CurrentUserContext` userId.
  - Revoke by handle fails (404/403) if handle belongs to another user.
  - Unit/integration coverage for cross-user revoke attempt.
- **Hints:** `SessionStore.list` / `revokeByHandle`, `AuthController`.
- **Completed:** 2026-10-09 — `list`/`revokeByHandle` take owner userId; foreign handle → 404; IT `listAndRevokeAreScopedToTheSignedInUser`.
- **Notes:** Same branch `feature/platform-improvement-s-a1-websocket-auth`.

### S-A3 — Admin-gate ops mutators + global settings + Demo Mode

- **Status:** `done`
- **Priority:** P0
- **Findings:** C3, H1
- **Goal:** Only admin can trigger backup/cleanup/logic-review/tuning/graduation resume or change session-timeout / Demo Mode.
- **Acceptance:**
  - Listed mutators call `requireAdmin` (or equivalent); non-admin gets 403.
  - Reads used by the Agents dashboard may stay session-gated if they leak no secrets beyond current behavior (document any change).
- **Hints:** `OpsController`, `CleanupController`, `LogicReviewController`, `PerformanceController`, `RecommendationController`, `SettingsController`, `DemoModeController`.
- **Completed:** 2026-10-09 — `requireAdmin` on listed POSTs/PUTs; GETs stay session-gated; IT `AdminMutatorGateIntegrationTest`.
- **Notes:** Same feature branch.

### S-A4 — Route portfolio-derived pushes to `sendToUser`

- **Status:** `done`
- **Priority:** P0
- **Finding:** H2
- **Goal:** Alerts that can hint at holdings go only to the affected user; true market-wide news may still broadcast.
- **Acceptance:**
  - Inventory of `sendToAll` call sites; portfolio-linked paths use `sendToUser`.
  - HoldingGuard-style path remains correct; `/test` push is admin-only or user-scoped.
- **Hints:** `NotificationService`, `PushController`, digest/breaking/cleanup schedulers.
- **Completed:** 2026-10-09 — ticker-linked `NotificationService` + holdings-impact breaking news → `sendToUser` via `PositionRepository.userIdsHoldingTicker`; `/api/push/test` scoped to caller; macro/crisis breaking, digest, cleanup remain `sendToAll`.
- **Notes:** Same feature branch.

### S-A5 — Gate all Haiku paths on Cost Governor

- **Status:** `done`
- **Priority:** P0
- **Finding:** H5
- **Goal:** At 95% budget, no Haiku call — including `generateBig()` fallbacks.
- **Acceptance:**
  - Every `haikuFallback.generate` path checks `allowPaidCall()` (or shared helper).
  - Unit tests: timeout/blank/primary-failure fallbacks stay local when budget blocked.
- **Hints:** `DefaultModelGateway.generateBig` / `escalate`.
- **Completed:** 2026-10-09 — `paidFallback` gates escalate + generateBig (permit timeout / primary failure / blank); throws when budget ≥95% and local unavailable.
- **Notes:** —

### S-A6 — Reject userless sessions; PDF magic; push unsubscribe ownership

- **Status:** `done`
- **Priority:** P0
- **Findings:** M2, M4
- **Goal:** Close leftover single-user auth holes.
- **Acceptance:**
  - Sessions without `userId` fail auth (and `/api/auth/status` is not `authenticated` with `user=null`).
  - PDF upload requires `%PDF` magic (not only content-type/filename).
  - Push unsubscribe only deletes the current user’s subscription for that endpoint.
- **Hints:** `SessionStore.validate`, `SessionAuthFilter`, `PortfolioImportController.isPdf`, `PushController` / `PushService`.
- **Completed:** 2026-10-09 — `validate` requires `userId`; `/status` only authenticated when AppUser resolves; PDF `%PDF` magic; push unsubscribe scoped by `userId`.
- **Notes:** Phase A complete.

---

## Phase B — Paper trust & learning (owner’s main product path)

### S-B1 — Trust scoreboard front-and-center

- **Status:** `done`
- **Priority:** P1
- **Goal:** Operator can answer “is the agent getting better?” without digging only into Agents.
- **Acceptance:**
  - Prominent UI (Home and/or Intelligence header): paper win rate, closed-trade sample size warning, Brier/calibration summary, graduation state, short trend (e.g. last 30d vs prior).
  - When sample size is too small, UI explicitly says results are not statistically meaningful.
  - Uses existing performance/calibration APIs where possible.
- **Hints:** `AgentPerformance`, `PaperInvestorScoreboard`, `PerformanceController`, graduation APIs; Agents page already has pieces — lift a compact “trust strip.”
- **Completed:** 2026-10-09 — `TrustScoreboard` on Home + Intelligence; accuracy API adds `prior30d` for 30d vs prior trend; sample-size copy when &lt;20 closed.
- **Notes:** —

### S-B2 — Paper-validation bar (real-money gate messaging)

- **Status:** `done`
- **Priority:** P1
- **Depends on:** S-B1 helpful but not required
- **Goal:** Product clearly framed as paper lab until a configurable trust bar clears.
- **Acceptance:**
  - Persistent UI copy: recommendations are paper-validated; not brokerage execution.
  - Configurable bar fields documented (e.g. min closed trades, max Brier, not FROZEN) — even if thresholds are config defaults first.
  - When bar not met: “Paper validation only — trust bar not cleared.”
- **Hints:** Frontend chrome + optional `app_settings` / env thresholds; align README tone on Intelligence.
- **Completed:** 2026-10-09 — `TrustBar` (pure evaluator) + `TrustBarService` + `GET /api/recommendations/trust-bar`; persistent `PaperLabBanner` under the top bar on every page; checklist in `TrustScoreboard` (Home + Intelligence); `TrustBarTest` (6).
- **Bar definition** (judged on the **current system's** paper book only; reuses the graduation ladder so “trusted” has one definition):

  | Check | Config key | Env var | Default |
  |---|---|---|---|
  | Graduation state | `argus.trust-bar.required-state` | `ARGUS_TRUST_BAR_REQUIRED_STATE` | `ACTIVE` (so SHADOW / PROBATION / FROZEN never clear) |
  | Closed paper trades | `argus.trust-bar.min-closed-trades` | `ARGUS_TRUST_BAR_MIN_CLOSED_TRADES` | `50` |
  | Paper win rate % | `argus.trust-bar.min-win-rate-pct` | `ARGUS_TRUST_BAR_MIN_WIN_RATE_PCT` | `55` |
  | Calibration (Brier) | `argus.trust-bar.max-brier` | `ARGUS_TRUST_BAR_MAX_BRIER` | `0.22` (0.25 = coin flip) |

  Thresholds are inclusive; a missing number fails its check. Not cleared → “Paper validation only — trust bar not cleared.” Cleared → “Trust bar cleared on paper — still advisory: Argus never places orders.” The banner's paper-lab statement shows even if the bar can't be loaded.
- **Notes:** Owner chose option (a): the banner covers **recommendations**; the holding guard (real holdings: broker stops, auto SELL after 15 min) is unchanged. Gating the guard's auto-decision on the bar would be a separate story if wanted. **Pre-existing failure, not S-B2:** `ChangeWatcherIntegrationTest` has 3 failing “fires only once per day” assertions on this branch *before* S-B2's changes too (verified by running it with S-B2 stashed); every other backend test passes (1157/1160).

### S-B3 — Post-trade learning narrative (win and loss)

- **Status:** `done`
- **Priority:** P1
- **Goal:** Every closed paper trade produces a short structured lesson: why entered, outcome, what changed (or why no change).
- **Acceptance:**
  - On paper close (win or loss), persist a lesson record linked to the trade/recommendation.
  - UI: readable narrative on Agents (and link from Intelligence thesis if present).
  - Logic Review / weight changes referenced when they fire; “no change” is an explicit outcome.
- **Hints:** `learning/*`, `PaperInvestorService`, Logic Review, Trade Journal snapshots.
- **Completed:** 2026-10-09 (Claude Code).
  - **Storage:** V87 `trade_lesson`, one row per closed `simulated_trades` row (unique `trade_id`), linked to `recommendation_id`.
  - **Writing lessons:** `learning/TradeLessonService` runs a scheduled pass every 5 minutes (`argus.lessons.*`). It writes missing lessons, backfilling 200 at a time, then settles "what changed".
    - It is deliberately **not** inside `PaperInvestorService.closeOne`, so a lesson can never slow down or break a close.
    - `LessonComposer` is pure and makes **no model calls**.
  - **Content:**
    - **Why entered:** the call, conviction, odds, the top 3 agents on the trade's side, and the thesis.
    - **Outcome:** the return and the return vs SPY, plus the exit reason.
    - **Lesson:** the Analyst's post-mortem on a loss; on a win, the agents that were right on direction.
  - **What changed** comes from activity after the close:
    - `logic_review` adopted proposals become `WEIGHTS_ADJUSTED`, which names the factors and flags the agents this trade relied on.
    - `learned_rule` activations and retirements become `RULE_ACTIVATED` / `RULE_RETIRED`.
    - Explicit `NO_CHANGE` is written only once both a logic review and an Agent 13 run have happened since the close. Until then the lesson is `PENDING`.
    - After 7 days with a job still missing, it becomes `NO_CHANGE` and names the job that didn't run.
  - **API:** `GET /api/learning/lessons?ticker=&limit=` and `GET /api/learning/lessons/trade/{tradeId}`.
  - **UI:**
    - Agents page: the "What the Investor learned" feed (`#lessons`), with result and what-changed filters plus paging.
    - The Investor record's expanded closed-trade row shows the lesson.
    - Intelligence ticker view: "Paper lessons on X" (up to 3), linking to the feed.
  - **Tests:** `LessonComposerTest` (11) and `TradeLessonIntegrationTest` (3). The full backend suite passes, 1174/1174.
- **Notes:** The `ChangeWatcherIntegrationTest` failures noted under S-B2 did not happen in this run (all 1174 passed), so they look flaky (time-of-day), not broken. Mini checks are in `docs/mac-mini-validation.md` §16.

### S-B4 — Pattern library consulted before next paper trade

- **Status:** `in-progress`
- **Priority:** P1
- **Depends on:** S-B3 (patterns need lessons/outcomes)
- **Goal:** Before opening a paper trade, agent looks up similar past setups and adjusts action (skip, size, stop, proceed).
- **Acceptance:**
  - Setup fingerprint stored (signals/regime/ticker traits — document schema).
  - Lookup API/service returns similar past outcomes + suggested adjustment.
  - Paper entry path consults it; decision logged (“matched pattern X → tightened stop”).
  - Empty library fails open (trade proceeds with “no prior pattern”).
- **Hints:** New table or extend learned rules; wire into `PaperInvestorService` / recommendation trigger.
- **Completed:** —
- **Notes:** —

### S-B5 — Intelligence as active thesis board

- **Status:** `not-started`
- **Priority:** P1
- **Goal:** Intelligence answers: buy/sell/hold, confidence, why, paper position/P&L if any, similar-pattern hint.
- **Acceptance:**
  - Actionable cards/rows show action + confidence + top contributing signals.
  - If paper book is in the name, show open/closed paper status and P&L.
  - Optional link into S-B4 pattern match when available.
  - Does **not** require human Take/Decline for the loop to work.
- **Hints:** Intelligence Today / TickerDetail; recommendation + paper-trade APIs.
- **Completed:** —
- **Notes:** —

### S-B6 — Per-stock / per-style strategy fit

- **Status:** `not-started`
- **Priority:** P2
- **Goal:** Track which playbooks win on which kinds of names (vol regime, sector, large vs high-beta, etc.).
- **Acceptance:**
  - Closed paper trades tagged with style dimensions.
  - Report/UI: strategy or signal family × style bucket win rates (with sample-size guards).
  - Recommendation/paper path can prefer the better-fitting playbook when enough sample exists.
- **Hints:** `strategy/*`, paper ledger, adaptive tuning — extend rather than replace.
- **Completed:** —
- **Notes:** —

### S-B7 — Strategy sandbox: shadow → promote or kill

- **Status:** `not-started`
- **Priority:** P2
- **Depends on:** S-B6 helpful
- **Goal:** New strategies run in shadow/paper only; promote into live Agent 5 weighting only after beating baseline with min sample; else kill.
- **Acceptance:**
  - Clear states: `SHADOW` / `CANDIDATE` / `PROMOTED` / `KILLED`.
  - Shadow results visible on Agents; no effect on live scores until promoted.
  - Promotion rule documented and enforced in code (min N, beat baseline metric).
- **Hints:** Academic strategies package; graduation patterns are a good model.
- **Completed:** —
- **Notes:** —

---

## Phase C — Multi-user hygiene

### S-C1 — Per-user journal, watchlist, notification prefs

- **Status:** `not-started`
- **Priority:** P2
- **Finding:** H3
- **Goal:** One friend’s decisions/prefs don’t rewrite everyone else’s.
- **Acceptance:** Journal/decisions, watchlist, notification prefs scoped by `user_id` (or documented admin-global watchlist universe).
- **Hints:** `docs/multi-user.md` §5.5; Flyway + `@TenantId` or explicit filters.
- **Completed:** —
- **Notes:** —

### S-C2 — Admin uninvite / disable user / revoke-all sessions

- **Status:** `not-started`
- **Priority:** P2
- **Finding:** M5
- **Goal:** Offboard a friend without SQL.
- **Acceptance:** Admin API + Profile UI: disable/uninvite; revoke all sessions for that user; disabled user cannot sign in.
- **Hints:** `AdminController`, `SessionStore.revokeAllForUser` already exists.
- **Completed:** —
- **Notes:** —

### S-C3 — Per-user AI / import soft quotas

- **Status:** `not-started`
- **Priority:** P2
- **Finding:** M1
- **Goal:** One invitee cannot burn the household Haiku/Gemma budget alone.
- **Acceptance:** Soft daily caps on Ask-AI / research / deep / LLM import; 429 or friendly error when exceeded; admin exempt or higher cap.
- **Hints:** Redis counters; `escalate` already bypasses BIG semaphore — consider aligning.
- **Completed:** —
- **Notes:** —

---

## Phase D — Later / optional

### S-D1 — Optional human Agree/Disagree overlay

- **Status:** `not-started`
- **Priority:** P3
- **Finding:** C4 (deprioritized vs original review)
- **Goal:** Operator can mark agreement with an agent call for regret overlay — **not** required for paper loop.
- **Acceptance:** Lightweight Agree/Disagree (+ optional note) on Intelligence; stored separately from Investor paper decisions; does not block paper trading.
- **Hints:** Reuse parts of `RecommendationCards` / `decideRecommendation` carefully; do not resurrect full old card stack unless useful.
- **Completed:** —
- **Notes:** Owner vision: paper loop is primary; this is optional.

### S-D2 — Align chrome copy with paper-lab positioning

- **Status:** `not-started`
- **Priority:** P3
- **Finding:** H4
- **Goal:** Metadata/PWA/UI say paper-validated intelligence lab, not alpha tip sheet / not brokerage advice.
- **Acceptance:** `layout` / `manifest` / Intelligence chrome updated; no “guaranteed edge” language.
- **Completed:** —
- **Notes:** —

### S-D3 — Frontend error boundary + visible refresh failures

- **Status:** `not-started`
- **Priority:** P3
- **Finding:** M6
- **Goal:** Failures don’t blank the app or silently keep stale numbers.
- **Acceptance:** `error.tsx` (and ideally `global-error.tsx`); key dashboards show “couldn’t refresh — showing data from …” on failure.
- **Completed:** —
- **Notes:** —

---

## Phase E — New agents (after learning loop works)

Do **not** start Phase E until S-B1 and S-B3 are `done` (and preferably S-B4 sketched). Prefer implementing Pattern Matcher / Scout **as services inside existing packages first**; promote to a numbered Agent only if they need their own schedule, DLQ, and Agents-page dossier.

### S-E1 — Agent 16 Pattern Matcher

- **Status:** `not-started`
- **Priority:** P2 (after S-B3/B4)
- **Goal:** Dedicated runtime that owns setup fingerprints + similarity search + pre-trade advice consumed by the paper Investor.
- **Acceptance:** Appears on Agents fleet; consulted on every new paper entry; outcomes feed its own accuracy (did “skip” advice avoid losses?).
- **Completed:** —
- **Notes:** May collapse into Trade Learner if a separate agent is overkill — decide at implementation time.

### S-E2 — Agent 17 Strategy Scout

- **Status:** `not-started`
- **Priority:** P2 (after S-B1)
- **Goal:** Periodically propose strategies from allowlisted sources into **SHADOW** only; never writes live Agent 5 weights.
- **Acceptance:** New candidates visible in sandbox UI; promotion only via S-B7 rules; Cost Governor respected for any LLM summarization.
- **Completed:** —
- **Notes:** Scraping must stay allowlisted domains — no open-ended web agent.

### S-E3 — Agent 18 Earnings Call Reader (optional)

- **Status:** `not-started`
- **Priority:** P3
- **Goal:** Earnings-call transcript digests as an Agent 5 signal, separate from Filings Reader.
- **Acceptance:** Signal weight + contribution in probability audit; skips tickers with no transcript source.
- **Completed:** —
- **Notes:** Skip entirely if Filings + calendar quiet periods are enough.

---

## Appendix A — Original findings index (Oct 2026)

Use for evidence; **stories above are the work queue.**

| ID | Sev | Finding | Story |
|---|---|---|---|
| C1 | Critical | Session list/revoke unscoped | S-A2 (**done**) |
| C2 | Critical | Unauthenticated WS portfolio queues | S-A1 (**done**) |
| C3 | Critical | Ops mutators not admin-gated | S-A3 (**done**) |
| C4 | Critical→P3 | Take/Decline UI dead (optional for owner vision) | S-D1 |
| H1 | High | Global settings / Demo Mode | S-A3 (**done**) |
| H2 | High | Broadcast push holdings hints | ~~S-A4~~ done |
| H3 | High | Shared journal/watchlist/prefs | S-C1 |
| H4 | High | Chrome positioning | S-D2 |
| H5 | High | Haiku fallbacks bypass budget | ~~S-A5~~ done |
| M1 | Medium | No per-user AI quotas | S-C3 |
| M2 | Medium | Userless sessions authenticate | ~~S-A6~~ done |
| M3 | Medium | Streams spine only 3 agents | (backlog — document hybrid; no story yet) |
| M4 | Medium | PDF magic / push unsub IDOR | ~~S-A6~~ done |
| M5 | Medium | No uninvite/disable | S-C2 |
| M6 | Medium | No error.tsx | S-D3 |
| M7 | Medium | Jobs use investor defaults | (fold into B stories when touching briefings) |
| L1–L4 | Low | Black Swan stub, Flyway admin email, markdown hrefs, CSRF | (backlog) |

---

## Appendix B — Doc sync

When renaming/moving this plan, update links in:

- `README.md`
- `docs/multi-user.md`
- `docs/deploy-runbook.md`
- `docs/design-terminal-noir.md`
- `_bmad-output/planning-artifacts/architecture.md`

---

## Next action for any new agent

1. Open this file.  
2. Find the first story with Status `in-progress` or `not-started` (see the Story board).  
3. Confirm with the user if the session doesn’t already say to start.  
4. Set `in-progress` → implement → `done` → **ask before the next story**.
