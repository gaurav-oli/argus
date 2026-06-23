---
baseline_commit: aaa1ae6
---

# Story 3.6: Portfolio chart

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want a chart of my total portfolio value over selectable time ranges,
so that I can see how my portfolio has performed (1D … 1Y) at a glance.

## Acceptance Criteria

1. **Range-selectable value chart (FR-4)** — the dashboard shows a total-portfolio-value chart with range toggles **1D, 1W, 1M, 3M, YTD, 1Y** (and "All"); picking a range re-slices the series to that window.
2. **TradingView Lightweight Charts (FR-4)** — rendered with the already-installed `lightweight-charts` (v5), matching the existing `PriceChart` prototype's theme handling (canvas colours from CSS vars, area series, transparent background).
3. **Real data** — the series is the persisted **portfolio value history** (CAD), not mock data. The chart fetches `GET /api/portfolio/value-history?range=<r>` (session-gated) and renders `{ date → totalValueCad }` points in ascending date order.
4. **History capture** — total portfolio CAD value is captured **once per day** (idempotent upsert keyed by date) by a scheduled job, plus an idempotent `capture()` the system can call; history accrues over time. (Intraday/1D granularity beyond the daily close is a documented later enhancement — 1D shows the latest captured point(s).)
5. **Graceful empties** — with no history yet the chart shows an empty/"no history yet" state, not an error; ranges with no points render empty rather than throwing.
6. **Money integrity** — values are `NUMERIC(20,2)`/`BigDecimal` server-side; the endpoint returns camelCase `{ date, totalValueCad }`.
7. **Tests** — backend: `capture()` upserts today's point (second call same day updates, no duplicate); `history(range)` filters to the window in ascending order; the endpoint returns the series session-gated. `./mvnw verify` green; frontend `lint` + `build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Value-history persistence** (AC: #3, #4, #6)
  - [x] `V8__portfolio_value_history.sql`: `portfolio_value_history(id, captured_on date NOT NULL UNIQUE, total_value_cad numeric(20,2) NOT NULL, created_at timestamptz)`. One point per day.
  - [x] `PortfolioValuePoint` entity + `PortfolioValuePointRepository` (`findByCapturedOn`, `findByCapturedOnGreaterThanEqualOrderByCapturedOnAsc`, `findAllByOrderByCapturedOnAsc`), mirroring the `AppSettings`/`Position` idiom.
- [x] **Task 2 — Capture + query service** (AC: #3, #4, #5)
  - [x] `PortfolioHistoryService`: `capture()` reads `LivePortfolioService.currentSnapshot().totalValueCad()` and upserts today's (America/Toronto) point; `history(Range)` returns the points in the window ascending. A `@Scheduled` daily capture (16:30 ET) calls `capture()` (exceptions caught — never crashes the runtime). `Range` enum (`D1,W1,M1,M3,YTD,Y1,ALL`) → start date from today.
- [x] **Task 3 — Endpoint** (AC: #1, #3, #6)
  - [x] `GET /api/portfolio/value-history?range=<r>` (default `M1`) on the portfolio value controller → `List<ValuePoint>` (`date`, `totalValueCad`), session-gated, RFC 9457, resource returned directly.
- [x] **Task 4 — Chart UI** (AC: #1, #2, #5)
  - [x] `features/portfolio/PortfolioChart.tsx`: mirror the `PriceChart` v5 idiom (createChart + AreaSeries + `cssVar`/`withAlpha` + ThemeProvider), but fetch real points via `getValueHistory(range)` and `setData`. Range toggles 1D/1W/1M/3M/YTD/1Y/All. Empty state when no points. `lib/apiClient.ts` gains `getValueHistory` + `ValuePoint`. Replace the dummy `PriceChart` slot on the portfolio page.
- [x] **Task 5 — Tests + verify** (AC: #7)
  - [x] Integration (Testcontainers): persist points across dates → `history`/endpoint returns only the in-range window ascending; `capture()` twice same day → single row, value updated. `./mvnw verify`; `npm run lint`/`build`.

## Dev Notes

### The data model (why a history table)
A value-over-time chart needs a stored series — the live snapshot (3.4) is transient. `portfolio_value_history` stores one CAD-value point per day (`captured_on` UNIQUE → idempotent upsert). A `@Scheduled` daily job captures the current `LivePortfolioService` total; `capture()` is also callable directly (and is what tests drive). History is naturally sparse at first and fills in over time. **Intraday 1D** granularity (multiple points/day) is deferred — 1D returns the latest captured point(s); documented.

### Builds on 3.4/3.5
- `portfolio/LivePortfolioService.currentSnapshot().totalValueCad()` is the value source for `capture()`. No change to it.
- Frontend mirrors `components/dashboard/PriceChart.tsx` (v5 `lightweight-charts` API + `cssVar`/`withAlpha` + `useTheme`) — reuse that exact pattern; swap `fullPriceSeries` mock for the fetched series. `lightweight-charts ^5.2.0` is already a dependency (no new install). Use `chart.addSeries(AreaSeries, …)`, `series.setData([{ time, value }])`, `timeScale().fitContent()`.

### Architecture / convention guardrails
- **Money:** `NUMERIC(20,2)`/`BigDecimal`. **Dates:** `date` (capture day) in America/Toronto; JSON ISO date string. [architecture.md#Format Patterns]
- **Flyway:** next free version is **V8** (V1–V7 exist); forward-only; `ddl-auto: validate`.
- **Scheduling:** `@Scheduled` runs on the virtual-thread `taskScheduler` (Story 1.5/1.6) — a daily cron is light; catch + log, never throw out of the scheduled method. [architecture.md#Process Patterns]
- **REST/session/Jackson 3** as established; chart is read-only GET.
- **Tests:** Testcontainers for the persistence/endpoint; JUnit Jupiter; no live network.

### Scope boundaries
- **Benchmark overlay (S&P 500 / TSX)** → Phase 3 (FR-4 defers it explicitly).
- **Intraday/1D tick-level history** → later enhancement (daily close for MVP).
- **Backfilling historical value** before capture began → not attempted; history starts accruing now.

### Files to touch
- **New (backend):** `portfolio/PortfolioValuePoint.java`, `portfolio/PortfolioValuePointRepository.java`, `portfolio/PortfolioHistoryService.java`, `resources/db/migration/V8__portfolio_value_history.sql`, test `portfolio/PortfolioHistoryIntegrationTest.java`.
- **Modified (backend):** `portfolio/PortfolioValueController.java` (add `/value-history`).
- **New (frontend):** `src/features/portfolio/PortfolioChart.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/app/(dashboard)/portfolio/page.tsx`.

### References
- [Source: epics.md#Epic 3 / Story 3.6] — range toggles, TradingView Lightweight chart.
- [Source: prd.md#FR-4] — ranges 1D…1Y; Lightweight Charts; benchmark overlay deferred (§6.2).
- [Source: architecture.md#Decision 7 (TradingView Lightweight Charts), #Format Patterns]; [Source: 3-4 snapshot, components/dashboard/PriceChart.tsx prototype].

## Dev Agent Record

### Agent Model Used
claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Completion Notes List
- `portfolio_value_history` (V8) + daily idempotent `capture()` (scheduled 16:30 ET) + `history(range)`; endpoint `GET /api/portfolio/value-history`.
- Real `PortfolioChart` (lightweight-charts v5) with 1D…1Y/All toggles, empty state; replaces the dummy `PriceChart` slot.

### File List
(see commit)

## Change Log
| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented portfolio value chart (FR-4): V8 value-history + capture/query service + endpoint + real Lightweight-Charts chart. Status → review (batch). |
