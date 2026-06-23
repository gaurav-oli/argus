---
baseline_commit: 52762b9
---

# Story 3.8: Health Score calculation

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want a single 0–100 Portfolio Health Score that's always visible,
so that I get an at-a-glance, credit-score-style read on my portfolio's health — the first number I see when I open Argus.

## Acceptance Criteria

1. **0–100 score from a deterministic rule engine (FR-6)** — the score is computed by an **auditable rule/weight engine, never an LLM** (project framing rule). It starts at 100 and applies point deductions for: **position concentration** (single-name + top-3), **diversification** (too few holdings), and **data-quality / pending actions** (positions flagged needs-review or FX-estimated). **Agent-sentiment / risk-alert / open-alert inputs are stubbed at 0** until Epics 4/6/8 exist (documented). Score is clamped to [0, 100].
2. **Always-visible, colour-thresholded (FR-6)** — the score shows in the **top bar** (web + mobile) with colour thresholds **80–100 green, 60–79 amber, below 60 red**.
3. **Breakdown is always available (FR-6)** — the engine produces, alongside the score, the **list of deductions** (each: code, label, points, specific reason, suggested fix) so the score is never shown without an explainable breakdown (the tap-to-expand widget itself is Story 3.9).
4. **Daily recompute + persistence** — the score is (re)computed and an idempotent daily point is stored (one row per day) so 3.9 can show a 30-day trend; a scheduled job captures it daily, and `GET /api/portfolio/health-score` returns the **current** computed score + deductions.
5. **Cost-basis weighting** — concentration/diversification use each holding's **CAD ACB** (always available from Story 3.2) as the weight basis, so the score is deterministic without live prices (market-value weighting is a later refinement; documented). An empty portfolio scores 100 (nothing at risk).
6. **Money/format** — weights/percentages computed with `BigDecimal`; score is an integer; endpoint camelCase, session-gated, RFC 9457.
7. **Tests** — backend unit: a concentrated single-name portfolio loses concentration points; a 1–2 holding portfolio loses diversification points; a well-diversified clean portfolio scores 100; needs-review/FX-estimated holdings lose data-quality points; score clamps at 0; empty → 100. Integration: `GET /health-score` returns score+deductions session-gated. `./mvnw verify` green; frontend `lint`+`build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Rule engine** (AC: #1, #3, #5): `HealthDeduction` record (`code,label,points,reason,suggestion`) + `HealthScoreResult` record (`score,deductions,computedAt`). `HealthScoreService.compute()` reads positions, derives CAD-ACB weights, applies the concentration/diversification/data-quality rules (each firing rule → a `HealthDeduction`), `score = clamp(100 − Σpoints)`. Agent/risk inputs are explicit no-op hooks (return no deductions yet).
- [x] **Task 2 — Daily persistence** (AC: #4): `V10__health_score.sql` → `health_score(id, scored_on date UNIQUE, score int, breakdown jsonb, created_at)`. `HealthScore` entity + repo; `HealthScoreService.capture()` upserts today's score + breakdown (JSON via Jackson 3, like `portfolio_imports.raw_holdings`); `@Scheduled` daily capture (06:00 ET per FR-6, after overnight agent runs in later epics).
- [x] **Task 3 — Endpoint** (AC: #2, #6): `GET /api/portfolio/health-score` → `HealthScoreResult` (current compute), session-gated, on the portfolio value controller (or a new health controller).
- [x] **Task 4 — Top-bar score** (AC: #2): `features/portfolio/HealthScoreBadge.tsx` fetches `getHealthScore()`, renders the number with the 80/60 colour thresholds; replace the mock score KPI in `components/shell/TopBar.tsx` with it. (Tap-to-breakdown + trend = Story 3.9.) `lib/apiClient.ts` gains `getHealthScore` + `HealthScoreResult`/`HealthDeduction` types.
- [x] **Task 5 — Tests + verify** (AC: #7): `HealthScoreServiceTest` (unit) + a health-score integration slice; `./mvnw verify`; `npm run lint`/`build`.

## Dev Notes

### Framing rule (non-negotiable)
The Health Score is **model-derived (rule/weight engine), never LLM-generated** — this is a core Argus framing decision (probabilities/scores are auditable, not produced by a language model). Keep the engine pure, deterministic, and unit-tested. [project memory: CRITICAL FRAMING]

### Rule set (MVP — extend as agents land)
- **Concentration (single-name):** largest CAD-ACB weight > 25% → deduct ~`round((w−0.25)×100)` capped (e.g. 20). Reason cites the actual ticker + %.
- **Concentration (top-3):** sum of top-3 weights > 60% → deduct capped (e.g. 20).
- **Diversification:** fewer than 5 holdings → deduct `(5 − n) × 4` (cap 16).
- **Data quality / pending actions:** each holding flagged `needsReview` or `fxEstimated` → deduct 2 each (cap 10); reason "confirm FX / review N positions".
- **Agent sentiment / open risk alerts / pending critical actions:** **0 for now** — explicit hooks returning no deductions; wired when Epics 4 (news), 6 (recommendations), 8 (alerts) exist. (FR-6 lists these; they're stubbed, not omitted.)
- Score = `clamp(100 − Σ points, 0, 100)`. Empty portfolio → no deductions → 100.

### Builds on prior stories
- Reads `Position` (`cadAcb`, `needsReview`, `fxEstimated`, `ticker`) — all persisted (3.1/3.2). Weighting on **CAD ACB** keeps it deterministic without the live feed (3.4 market value is a later refinement). No change to existing services.
- Persistence mirrors `portfolio_value_history` (3.6): one row/day, idempotent upsert, `@Scheduled` daily. Breakdown stored as JSON (`@JdbcTypeCode(SqlTypes.JSON)` String + Jackson-3 serialize — same idiom as `PortfolioImport.rawHoldings`).
- Frontend: `TopBar.tsx` currently uses `mockData.healthScore` — swap the score KPI for a real `HealthScoreBadge`. Keep `Sensitive` (tap-to-reveal) + thresholds.

### Architecture / convention guardrails
- **No LLM** in the score path. **Money:** `BigDecimal`; score `int`. **Flyway:** next free version **V10** (V1–V9 exist). **REST/session/Jackson 3** as established. `@Scheduled` on the virtual-thread scheduler; catch+log. [architecture.md]
- **Tests:** JUnit Jupiter; engine is pure (construct `Position`s via ctor + `updateAcbCaches` to set `cadAcb`); Testcontainers for the endpoint slice; no live network.

### Scope boundaries
- **Breakdown widget + 30-day trend + tap-to-expand** → **Story 3.9** (this story produces the deductions + stores daily points; 3.9 renders them).
- **Market-value weighting / sector diversification (GICS)** → later (cost-basis weight + holding count for MVP; "sector" needs a sector data source — Epic 4+).
- **Agent/alert-driven score inputs** → Epics 4/6/8 (stub hooks now).

### Files to touch
- **New (backend):** `portfolio/HealthDeduction.java`, `portfolio/HealthScoreResult.java`, `portfolio/HealthScore.java`, `portfolio/HealthScoreRepository.java`, `portfolio/HealthScoreService.java`, `resources/db/migration/V10__health_score.sql`, tests `portfolio/HealthScoreServiceTest.java`, `portfolio/HealthScoreIntegrationTest.java`.
- **Modified (backend):** the portfolio value controller (add `GET /health-score`).
- **New (frontend):** `src/features/portfolio/HealthScoreBadge.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/components/shell/TopBar.tsx`.

### References
- [Source: epics.md#Epic 3 / Story 3.8] — 0–100 from concentration/diversification/risk/agent signals; always visible; colour thresholds; daily recompute.
- [Source: prd.md#F2 / FR-6] — inputs (concentration, sector diversification, agent sentiment, open risk alerts, pending actions); thresholds 80/60; never shown without breakdown; daily.
- [Source: project memory — CRITICAL FRAMING] scores are model-derived, never LLM. [Source: 3-2 (cadAcb), 3-6 (daily-capture pattern), TopBar prototype].

## Dev Agent Record

### Agent Model Used
claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Completion Notes List
- Deterministic rule engine (concentration single + top-3, diversification, data-quality; agent/risk hooks stubbed) → 0–100 score + explained deductions; CAD-ACB weighted.
- V10 daily `health_score` (idempotent upsert, scheduled 06:00 ET) for trend; `GET /api/portfolio/health-score`; real score in the top bar with 80/60 thresholds.

### File List
(see commit)

## Change Log
| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented health score calculation (FR-6): rule engine + V10 daily persistence + endpoint + top-bar badge. Status → review (batch). |
