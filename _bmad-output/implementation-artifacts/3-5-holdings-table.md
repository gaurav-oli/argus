---
baseline_commit: 94969be
---

# Story 3.5: Holdings table

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want a sortable, colour-coded table of all my holdings with live value and P&L,
so that I can scan every position — what it's worth, how it's moving today and overall, and how much of my portfolio it is — at a glance.

## Acceptance Criteria

1. **Columns (FR-3)** — the table shows, per position: **ticker**, **company name**, **shares**, **cost basis** (trade currency), **current price**, **current/market value**, **day P&L ($)**, **day P&L (%)**, **total P&L ($)**, **total P&L (%)**, and **portfolio weight (%)**.
2. **Sortable (FR-3)** — clicking any column header sorts the rows by that column; clicking again toggles ascending/descending; the active column + direction is indicated. Null values sort last regardless of direction.
3. **Colour-coded (FR-3)** — gains render in the `gains` token, losses in `losses`, applied consistently to both day and total P&L ($ and %). Zero is neutral.
4. **Live, single feed (FR-2 reuse)** — the table is driven by the **same `/topic/portfolio` snapshot** as the Story-3.4 value summary (initial `GET /api/portfolio/value` then STOMP updates) — no second price feed. A position with no price yet shows `—` for price/value/P&L, never a wrong 0.
5. **Mobile expand (UX-DR4)** — on phone widths the table collapses to the primary columns (ticker, value, total P&L); tapping a row expands the secondary columns (shares, cost, price, day P&L, weight). Desktop shows all columns inline.
6. **Day P&L from previous close** — day P&L = `(price − previousClose) × shares`, day P&L % = `(price − previousClose) ÷ previousClose × 100`. The previous close comes from the **key-gated Finnhub `/quote`** path (seeded on feed start; null without a key) → day P&L shows `—` when unavailable. **Total P&L %** = `totalPnl ÷ costBasis × 100`. **Weight %** = `position CAD market value ÷ portfolio total CAD value × 100`.
7. **Tests** — backend unit: snapshot computes day P&L + day-P&L% from a recorded previous close, total-P&L%, and weight%; a position with no previous close has null day P&L; weight sums to ~100% across priced positions. Frontend `lint` + `build` clean. `./mvnw verify` green (no regressions to 3.1–3.4).

## Tasks / Subtasks

- [x] **Task 1 — Extend the live snapshot model** (AC: #1, #6)
  - [x] Add to `PositionValue`: `companyName`, `totalPnlPercent`, `previousClose`, `dayPnl`, `dayPnlPercent`, `weightPercent` (keep existing fields). Money scale 2, percents scale 2, HALF_UP.
  - [x] `LivePortfolioService`: add a `previousClose` map + `recordPreviousClose(ticker, prevClose)`; compute day P&L / day-P&L% (when prevClose known), total-P&L%, and — after portfolio totals are known — `weightPercent` (two-phase: build rows, then `withWeight`). Null-safe throughout (unpriced / no-prevClose → null fields, excluded from weight base).
- [x] **Task 2 — Previous-close seam** (AC: #6)
  - [x] Extend `PriceFeed.start(...)` to also deliver previous closes (a `BiConsumer<String,BigDecimal>`); `FinnhubPriceFeed` fetches Finnhub `/quote` (`pc`) per held symbol on start (key-gated, resilient → on failure just no prevClose). `PriceFeedStarter` wires it to `LivePortfolioService::recordPreviousClose`. No key ⇒ no prevClose ⇒ day P&L null (graceful).
- [x] **Task 3 — Holdings table UI** (AC: #1–#5)
  - [x] `features/portfolio/HoldingsTable.tsx`: initial `getPortfolioValue()` + `subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", …)`; client-side sort (column + direction state, nulls last); gain/loss colour tokens; responsive — secondary columns hidden on mobile with a tap-to-expand row. Mirror `PortfolioValue.tsx` patterns; unsubscribe on unmount.
  - [x] Extend `lib/apiClient.ts` `PositionValue` with the new fields. Place the table on `app/(dashboard)/portfolio/page.tsx` (replacing the dummy treemap's slot or beside it).
- [x] **Task 4 — Tests + verify** (AC: #7)
  - [x] `LivePortfolioServiceTest`: add day P&L / day-P&L% (with `recordPreviousClose`), total-P&L%, weight% assertions; a no-prevClose case → null day P&L. `./mvnw verify`; `npm run lint`/`build`.

## Dev Notes

### Builds directly on Stories 3.4 + 3.2 (read these — they change)
- `portfolio/LivePortfolioService.java` — extend `currentSnapshot()` to compute the new fields; add the `previousClose` map + `recordPreviousClose`. Preserve 3.4 behaviour: in-memory prices, push to `/topic/portfolio`, CAD via `FxRateService`, totals only include priced positions. **Weight needs portfolio total first → build rows then apply `withWeight`** (don't try to compute weight in a single pass).
- `portfolio/PositionValue.java` — record extended (additive). The `withWeight` copy-helper sets `weightPercent` after totals are known.
- `marketdata/PriceFeed.java` + `FinnhubPriceFeed.java` + `portfolio/PriceFeedStarter.java` — `start(...)` gains a previous-close consumer; the Finnhub `/quote` fetch is the only new key-gated I/O (mirror the WS gating — dormant without `argus.finnhub.api-key`).
- Frontend `PortfolioValue.tsx` (pattern), `wsClient.ts` (`subscribeToTopic`), `apiClient.ts` (`PositionValue`/`getPortfolioValue`), `AnimatedNumber.tsx` (optional for cells), `app/(dashboard)/portfolio/page.tsx`.

### Architecture / convention guardrails (mandatory)
- **Money/percent:** `BigDecimal`/`NUMERIC` server-side, never float; display scale 2; divide-by-zero guarded (cost 0 → null %, total value 0 → null weight). [architecture.md#Format Patterns]
- **One live feed:** the table consumes the existing `/topic/portfolio` snapshot via `LivePushService`/STOMP — do **not** open a second subscription channel or feed. [architecture.md#Decision 4]
- **No new DB / migration** — all live values are transient/in-memory; next free Flyway version stays **V8**.
- **REST/JSON:** camelCase (Jackson 3), resource returned directly, session-gated; the value endpoint already exists (3.4) — 3.5 only enriches the payload.
- **Frontend:** plain React + typed `apiClient` + `wsClient` (no new state lib); tokens `gains`/`losses`/`text-*`; responsive per UX-DR4 (mobile bottom-nav layout). [Stories 1.7, 3.4]
- **Tests:** JUnit Jupiter (no AssertJ); unit-test the compute by `recordPreviousClose` + `onPriceTick` (no live network); existing Testcontainers slices must stay green.

### Scope boundaries (don't over-build)
- **Portfolio value chart** over time ranges → **Story 3.6**.
- **Manual add/edit/remove** of positions → **Story 3.7** (this table is read-only display).
- **Health score** column/badge → 3.8/3.9.
- **Live Finnhub `/quote` + WS validated against the real feed** → laptop dev key / Mini (the compute is the tested part).
- The dummy `HoldingsTreemap` (Story 1.7) may remain as an allocation viz; this story delivers the real tabular data.

### Files to touch
- **Modified (backend):** `portfolio/PositionValue.java`, `portfolio/LivePortfolioService.java`, `marketdata/PriceFeed.java`, `marketdata/FinnhubPriceFeed.java`, `portfolio/PriceFeedStarter.java`; test `portfolio/LivePortfolioServiceTest.java`.
- **New (frontend):** `src/features/portfolio/HoldingsTable.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/app/(dashboard)/portfolio/page.tsx`.

### References
- [Source: epics.md#Epic 3 / Story 3.5] — columns, sort, colour, mobile expand.
- [Source: prd.md#FR-3] — full column list incl. day & total P&L ($/%), weight; sort; colour; mobile tap-to-expand. [FR-2] live ≤1s; [A-1] recent FX.
- [Source: architecture.md#Decision 4 (live pub/sub), #Format Patterns]; [Source: 3-4-real-time-portfolio-value.md] — snapshot model, price-feed seam, `LivePortfolioService`.

## Dev Agent Record

### Agent Model Used
claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References
- `./mvnw verify` green; `npm run lint` + `npm run build` clean. (Filled at commit.)

### Completion Notes List
- Snapshot extended with company name, day P&L (from previous close), total-P&L %, and weight % (two-phase weight after totals). Previous close seeded via a key-gated Finnhub `/quote` fetch; null/graceful without a key.
- Sortable, colour-coded, mobile-expandable `HoldingsTable` driven by the single live `/topic/portfolio` snapshot.

### File List
(see commit)

## Change Log
| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented holdings table (FR-3): snapshot day P&L / total-P&L% / weight% + previous-close seam + sortable live table. Status → review (Epic-3-remainder batch review). |
