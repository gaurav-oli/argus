---
baseline_commit: 24d255f
---

# Story 3.9: Health Score breakdown widget

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to tap the Health Score and see exactly why it isn't 100 — each point deduction with a specific reason and a suggested fix, plus a 30-day trend,
so that I know what's dragging my score down and how to improve it.

## Acceptance Criteria

1. **Tap-to-breakdown (FR-7)** — tapping the always-visible Health Score (top bar) opens a breakdown panel.
2. **Every deduction explained (FR-7)** — the panel lists **each** deduction with its **points**, a **specific reason** (e.g. "−20: AAPL is 90% of your portfolio") and an **actionable suggested fix** — not vague category labels. When the score is 100, the panel shows a positive "nothing dragging it down" state.
3. **30-day trend (FR-7)** — the panel shows a 30-day score trend (a small sparkline) and a **direction** label — improving / stable / declining — derived from the series.
4. **Trend data** — `GET /api/portfolio/health-score/history?days=30` (session-gated) returns the stored daily score points (`{ date, score }`) ascending; reuses the Story-3.8 `health_score` table.
5. **Reuses 3.8** — the deductions come from the existing `GET /api/portfolio/health-score` (no recompute duplication); the widget composes the two endpoints.
6. **Polish (UX-DR5/DR9)** — the widget is a frosted/elevated panel anchored to the badge, dismissible (click-away/Esc), using the theme tokens + colour thresholds; accessible (button semantics).
7. **Tests** — backend: history endpoint returns the windowed series ascending, session-gated; empty history → empty array (not error). Frontend `lint`+`build` clean. `./mvnw verify` green.

## Tasks / Subtasks

- [x] **Task 1 — History endpoint** (AC: #3, #4): `HealthScoreService.history(int days)` → `List<HealthPoint>` (`date, score`) from `health_score` for the last N days ascending (reuse `findByScoredOnGreaterThanEqualOrderByScoredOnAsc`). `GET /api/portfolio/health-score/history?days=30` (default 30, clamped) on the value controller. `HealthPoint` record.
- [x] **Task 2 — Breakdown widget** (AC: #1, #2, #3, #6): `features/portfolio/HealthScoreBreakdown.tsx` — a dismissible popover showing the deduction list (points, reason, suggestion) or a clean-score state, plus a compact SVG sparkline of the 30-day series + a direction label (compare latest vs earliest). `HealthScoreBadge` becomes a button toggling it (click-away + Esc to close). `lib/apiClient.ts` gains `getHealthScoreHistory` + `HealthPoint`.
- [x] **Task 3 — Tests + verify** (AC: #7): backend history integration (windowing + session gate + empty); `./mvnw verify`; `npm run lint`/`build`.

## Dev Notes

### Builds entirely on Story 3.8
- Deductions are already produced by `HealthScoreService.compute()` and returned by `GET /api/portfolio/health-score` — the widget just **renders** them; do not recompute or duplicate the rules.
- Daily points are already persisted in `health_score` (V10, idempotent daily capture). 3.9 only **reads** them for the trend — no new table/migration. Next free Flyway version stays **V11**.
- `HealthScoreBadge` (3.8, in `TopBar`) already fetches the score; extend it to also open the breakdown. Keep the 80/60 colour thresholds + `Sensitive` (tap-to-reveal) behaviour.

### Trend direction
Derive from the series in the UI: compare the latest score to the earliest in-window (or ~7-day-ago) point — **improving** if up by ≥2, **declining** if down by ≥2, else **stable**. Sparkline is a small inline SVG (no charting lib needed for a tiny line; `lightweight-charts` is overkill here). With little history the sparkline renders what exists; an empty series shows "trend builds daily".

### Architecture / convention guardrails
- **No recompute in 3.9** — compose `/health-score` + `/health-score/history`. **REST/session/Jackson 3** as established; `days` clamped (e.g. 1–365). **No LLM.**
- **Frontend:** plain React + typed client; popover dismiss via click-away + `Escape`; theme tokens; button/aria semantics for the trigger. [UX-DR5 widget, UX-DR9 polish]
- **Tests:** Testcontainers for the history endpoint (seed `health_score` rows, query window); JUnit Jupiter; no live network.

### Scope boundaries
- **No new scoring rules** (that's 3.8); 3.9 is presentation + the read-only trend endpoint.
- **Intraday score** / per-signal drill-down beyond the deduction list → out.
- **Agent-signal deductions** appear automatically once Epics 4/6/8 add them to 3.8's engine — the widget already renders whatever deductions exist.

### Files to touch
- **New (backend):** `portfolio/HealthPoint.java`; test `portfolio/HealthScoreHistoryIntegrationTest.java` (or extend `HealthScoreIntegrationTest`).
- **Modified (backend):** `portfolio/HealthScoreService.java` (`history`), `portfolio/PortfolioValueController.java` (`/health-score/history`).
- **New (frontend):** `src/features/portfolio/HealthScoreBreakdown.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/features/portfolio/HealthScoreBadge.tsx`.

### References
- [Source: epics.md#Epic 3 / Story 3.9] — tap → every deduction with reason + fix; 30-day trend.
- [Source: prd.md#FR-7] — itemized deductions with specific explanation + actionable fix; 30-day trend (improving/stable/declining); score never shown without breakdown.
- [Source: 3-8-health-score-calculation.md] — `HealthScoreResult`/deductions, `health_score` daily table, `HealthScoreBadge`. [UX-DR5/DR9].

## Dev Agent Record

### Agent Model Used
claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Completion Notes List
- `GET /api/portfolio/health-score/history?days=30` (reads V10 `health_score`); `HealthScoreBreakdown` popover lists deductions + a 30-day SVG sparkline + direction; `HealthScoreBadge` made tappable (click-away/Esc).
- No recompute/duplication — composes the existing score + new history endpoint.

### File List
(see commit)

## Change Log
| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented health-score breakdown widget (FR-7): history endpoint + tap-to-expand deductions + 30-day trend. Status → review (batch). |


## Code Review (2026-06-23, Epic-3 batch)

Reviewed in the combined 3.4–3.9 adversarial batch review (Blind + Edge + Acceptance Auditor, Opus 4.8). **0 High AC violations**; verdict pass. Fixes applied in-batch and deferrals logged in `deferred-work.md`. Status → done.
