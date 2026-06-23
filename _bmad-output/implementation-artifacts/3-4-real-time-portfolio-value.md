---
baseline_commit: 6d262c5
---

# Story 3.4: Real-time portfolio value

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want my total portfolio value and per-position P&L to update live during market hours,
so that I see my portfolio move in real time without refreshing.

## Acceptance Criteria

1. **Live value + P&L from price ticks** — **Given** held positions and an incoming price tick for one of their tickers, **When** the tick is processed, **Then** that position's market value (`shares × price`, trade currency) and total P&L (`value − costBasis`) recompute, the portfolio totals recompute, and an updated snapshot is **pushed over STOMP within 1s** (`/topic/portfolio`). (FR-2)
2. **CAD + USD display** — Every value/P&L in the snapshot carries both its trade-currency amount and a **CAD equivalent** using a recent USD/CAD rate (not per-tick — a recent cached rate; see Dev Notes). CAD positions use 1.0. (FR-2)
3. **After-hours labelling** — Each position price and the snapshot carry a flag indicating whether the price is from **regular market hours** or **after-hours**; after-hours prices are clearly labelled (and rendered with reduced prominence in the UI). (FR-2)
4. **Initial snapshot endpoint** — `GET /api/portfolio/value` (session-gated) returns the current portfolio snapshot (last-known prices; `price`/`value` null for a ticker with no price yet) so the UI has a value to render before the first live tick. RFC 9457 errors; money as `BigDecimal`/`NUMERIC`.
5. **Price-feed seam, live impl key-gated** — Prices arrive through a `PriceFeed` seam. A `FinnhubPriceFeed` (Finnhub trade WebSocket) implements it and is **active only when a Finnhub API key is configured** (the laptop dev `.env` free key, or the Mini); with no key it stays inactive and the platform still runs (snapshot just has null prices). The live value/compute/push logic is independent of the feed and fully unit-testable by driving ticks directly. (architecture: `marketdata` = Finnhub WS prices)
6. **Smooth UI updates** — The portfolio screen shows a **live value summary** (total value, total P&L with gain/loss colour) that updates on each pushed snapshot using the existing `AnimatedNumber` (count up/down, no jump cuts), subscribed via the existing `wsClient`/STOMP. After-hours state is visibly indicated.
7. **Tests** — Backend unit: a tick updates the right position's value/P&L and the portfolio totals; CAD conversion; after-hours vs regular classification via an injected clock; unknown/again ticker handled; snapshot endpoint shape. Integration (Testcontainers, mirror existing portfolio tests, stub the FX client): import holdings → drive ticks through the service → assert the pushed/snapshot values. **No live network** — the `PriceFeed` is faked in tests. `./mvnw verify` green; frontend `lint` + `build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Market clock** (AC: #3)
  - [x] `MarketClock` (in `marketdata`): `boolean isRegularHours(Instant)` for US Eastern regular session (Mon–Fri 09:30–16:00 ET, DST-aware via `America/New_York`). Holiday calendar is out of scope (note it); weekend/time check only. Inject a `Clock` so tests are deterministic.
- [x] **Task 2 — Live value engine** (AC: #1, #2, #3)
  - [x] `LivePortfolioService` (in `portfolio`): keeps an in-memory `ticker → (price, asOf, afterHours)` map; `onPriceTick(ticker, price, instant)` updates it, recomputes the snapshot, and pushes via `LivePushService.publish("/topic/portfolio", snapshot)`. Throttle/coalesce is optional (single-user volume is low) — keep it simple.
  - [x] `PortfolioSnapshot` + `PositionValue` records (camelCase): per-position `{ ticker, shares, price, marketValue, costBasis, totalPnl, currency, cadMarketValue, cadPnl, afterHours, asOf }` + portfolio totals `{ totalValueCad, totalCostCad, totalPnlCad, asOf, anyAfterHours }`. Value = `shares × price`; P&L = `value − costBasis`; CAD via a recent USD/CAD from `FxRateService` (see Dev Notes); money `BigDecimal` (scale 2 for display amounts, HALF_UP).
  - [x] Reads positions via `PositionRepository`; uses the 3.2 `costBasis`/`cadAcb` caches for the cost side.
- [x] **Task 3 — Price-feed seam + Finnhub impl** (AC: #5)
  - [x] `PriceFeed` interface (`marketdata`): lifecycle to subscribe to a set of tickers and deliver ticks to `LivePortfolioService`. `FinnhubPriceFeed` implements it over the Finnhub trade WS (`wss://ws.finnhub.io?token=KEY`), parsing `{"type":"trade","data":[{"s","p","t"}]}`, reconnect on drop. **Gate activation** on `argus.finnhub.api-key` being present (e.g. `@ConditionalOnProperty` / a guard) so dev/test/no-key contexts don't open a socket. Subscribe to the tickers of current holdings.
  - [x] Resilience: connection failure logs + retries; never crashes the app; absence of a key = inactive, not an error.
- [x] **Task 4 — Snapshot endpoint** (AC: #4)
  - [x] `GET /api/portfolio/value` on a portfolio controller (session-gated under `/api/portfolio`) → `LivePortfolioService.currentSnapshot()`. Returns the resource directly; camelCase.
- [x] **Task 5 — Frontend live value** (AC: #6)
  - [x] `lib/apiClient.ts`: `PortfolioSnapshot`/`PositionValue` types + `getPortfolioValue()`.
  - [x] `features/portfolio/PortfolioValue.tsx`: fetch the initial snapshot (`getPortfolioValue`), then `subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", …)` for live updates; render total value + total P&L with `AnimatedNumber`, gain/loss tokens, and an after-hours indicator. Unsubscribe on unmount (the `wsClient` `disconnect`). Place it prominently on `app/(dashboard)/portfolio/page.tsx` (above the importer).
- [x] **Task 6 — Tests + verify** (AC: #7)
  - [x] Unit: `MarketClockTest` (regular vs after-hours vs weekend with a fixed `Clock`), `LivePortfolioServiceTest` (tick → value/P&L/totals; CAD conversion with a stubbed FX; after-hours flag; tick for a non-held ticker is ignored). Integration (mirror `CorporateActionIntegrationTest`, `@MockitoBean FxRateClient`): import → drive `onPriceTick` → `GET /api/portfolio/value` reflects the values; endpoint session-gated.
  - [x] `./mvnw verify` green; `npm run lint` + `npm run build` clean.

## Dev Notes

### ⭐ Decisions to confirm
1. **Price-feed scope (recommended): build the deterministic live-value engine + STOMP push behind a `PriceFeed` seam, with `FinnhubPriceFeed` key-gated.** Tests drive ticks directly (no live socket). The real Finnhub WS connection is a thin layer validated with the laptop dev free key (or on the Mini) — same engine-plus-seam pattern as the 3.2 FX client and 3.3 detector. *(vs. requiring a live WS now — flaky, key-bound.)*
2. **"Recent FX" for CAD display (recommended): reuse `FxRateService` (Bank of Canada, nearest-prior business day) for the current USD/CAD** rather than standing up Finnhub hourly FX (PRD A-1). BoC is already built (3.2), needs no key, and "FX doesn't move fast enough to need per-tick" (A-1's own rationale). A live Finnhub FX poller can replace it later if desired.
3. **Day P&L deferred to 3.5.** 3.4 delivers total value + **total** P&L (value − cost). Day P&L needs previous-close (a Finnhub `/quote` `pc`) and lands with the holdings table (3.5), which lists day & total P&L per FR-3.

### Scope boundaries (don't over-build)
- **Full sortable holdings table** (ticker/name/shares/cost/value/day&total P&L/weight, sort, mobile expand) → **Story 3.5**. 3.4 ships a value *summary* + the per-position values in the snapshot the table will consume.
- **Portfolio value chart** over time ranges → **Story 3.6**.
- **Day P&L / previous close** → 3.5 (see Decision 3).
- **Per-tick FX**, **holiday-aware market calendar**, and a **live Finnhub FX poller** → out (recent cached BoC rate + weekday/time clock suffice for MVP; note the holiday gap).
- **Health score** → 3.8.

### Builds on 3.1/3.2/3.3 (read these)
- `portfolio/Position.java` — source of `shares`, `costBasis`/`costBasisCurrency`, `cadAcb` (the cost side of P&L); read-only here.
- `common/LivePushService.java` — `publish(topic, payload)`; push snapshots to `/topic/portfolio`. Do not bypass it.
- `config/WebSocketConfig.java` — broker `/topic`, endpoint `/ws`; already wired (Story 1.6). No change expected.
- `marketdata/FxRateService.java` — reuse for the recent USD/CAD (add a small `currentUsdCad()` that calls `usdCadOn(today)` if a dedicated method helps).
- Frontend `lib/wsClient.ts` (`subscribeToTopic`), `components/ui/AnimatedNumber.tsx`, `lib/apiClient.ts`, `app/(dashboard)/portfolio/page.tsx` — extend; mirror the plain-React + typed-client patterns from `ImportStatement.tsx`/`CorporateActions.tsx`.
- **Regression watch (Story 1.5/1.6 gotcha):** enabling more scheduling/async must not disturb the virtual-thread `taskScheduler`; the WS feed should run on its own thread/executor, not hijack `@Scheduled` infrastructure.

### Architecture / convention guardrails (mandatory)
- **Money:** `BigDecimal`/`NUMERIC`, never float; carry currency; display amounts scale 2 HALF_UP. [architecture.md#Format Patterns]
- **Live fan-out:** STOMP `/topic/...` via `LivePushService`; throwaway live frames (dropped frames are harmless) — do NOT persist snapshots. [architecture.md#Decision 4: pub/sub for live dashboard]
- **REST:** `/api/portfolio/value`, resource returned directly, RFC 9457; session-gated by `SessionAuthFilter` (not allowlisted). camelCase JSON (Jackson 3, `tools.jackson`).
- **External feed:** key-gated, resilient, never crashes the runtime; agents/feeds catch → log → retry (Resilience4j is Epic 4 / GAP-4 — not required for this single WS). [architecture.md#Process Patterns, #GAP-4]
- **No new DB:** 3.4 adds no migration (live prices are in-memory/transient). Next free Flyway version remains **V8** for later stories.
- **Tests:** JUnit Jupiter (no AssertJ); inject a `Clock` and a fake `PriceFeed`/stub `FxRateClient` — no live sockets/network; Testcontainers for the integration slice; auth via `/api/auth/pin` + `/api/auth/login`.

### Files to touch
- **New (backend):** `marketdata/MarketClock.java`, `marketdata/PriceFeed.java`, `marketdata/FinnhubPriceFeed.java`, `portfolio/LivePortfolioService.java`, `portfolio/PortfolioSnapshot.java`, `portfolio/PositionValue.java`, a portfolio value controller (or add `GET /value` to an existing portfolio controller), tests `MarketClockTest`, `LivePortfolioServiceTest`, integration additions.
- **Modified (backend):** `marketdata/FxRateService.java` (optional `currentUsdCad()`), `application.yml` (`argus.finnhub.api-key` placeholder, ws enable flag), `.env.example` already has `FINNHUB_API_KEY`.
- **New (frontend):** `src/features/portfolio/PortfolioValue.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/app/(dashboard)/portfolio/page.tsx`.

### Latest tech notes
- **Finnhub trade WS:** `wss://ws.finnhub.io?token=API_KEY`; subscribe by sending `{"type":"subscribe","symbol":"AAPL"}`; trades arrive as `{"type":"trade","data":[{"s":"AAPL","p":189.3,"t":<ms>,"v":..}]}`; also sends `{"type":"ping"}`. Free tier covers US stocks. Use a WebSocket client (Spring `WebSocketClient`/`StandardWebSocketClient` or the JDK `java.net.http.WebSocket`); reconnect with backoff. Keep the key out of logs.
- **Market hours:** US regular session 09:30–16:00 **America/New_York** (DST-aware via `ZoneId`); weekends closed. Pre/post-market and holidays are not modeled in MVP (documented gap).

### Project Structure Notes
- Price feed + market clock live in `com.argus.marketdata`; the value/compute/push service in `com.argus.portfolio` (it reads positions); frontend `features/portfolio`. Matches `architecture.md#Requirements → Structure Mapping` (F1 Portfolio → `portfolio` + `marketdata`). No structure variance; no migration.

### References
- [Source: epics.md#Epic 3 / Story 3.4] — story + BDD ACs (1s update, smooth numbers, after-hours label).
- [Source: prd.md#FR-2] — live value + per-position P&L; ≤1s per Finnhub WS tick; smooth animation; after-hours labelled; CAD+USD via a recent FX rate [A-1: hourly, not per-tick].
- [Source: architecture.md#Decision 4 (pub/sub for live UI), #Decision 6 (REST+STOMP), #marketdata package, #GAP-4 (Finnhub resilience)].
- [Source: 3-1/3-2/3-3 stories] — Position/cost caches, `LivePushService`, `wsClient`, `AnimatedNumber`, Jackson-3 + test patterns; the dev `.env` free Finnhub key (project memory / topology).

## Open Questions (for the user — resolved 2026-06-22)
1. **Live feed scope: ✅ RESOLVED → engine + STOMP push behind a `PriceFeed` seam, `FinnhubPriceFeed` key-gated**; tests drive a fake feed (no live socket); real WS validated later with the dev key / on the Mini.
2. **CAD display FX source: ✅ RESOLVED → reuse `FxRateService` (Bank of Canada, recent daily).** No Finnhub FX poller.
3. **Day P&L: ✅ deferred to Story 3.5** (needs previous-close); 3.4 ships total value + total P&L.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 125 tests, 0 failures** (+9 vs the 116 after 3.3).
- `npm run lint` clean; `npm run build` (Next 16 / Turbopack) clean.
- Decisions confirmed (user): engine + key-gated `PriceFeed` seam; CAD via Bank-of-Canada `FxRateService`.

### Completion Notes List

- **Engine, not a socket:** `LivePortfolioService.onPriceTick(ticker, price, when)` updates an in-memory price map, recomputes a `PortfolioSnapshot` (per-position market value + total P&L, CAD totals via the 3.2 `cadAcb` cost + a recent BoC USD/CAD), and pushes to `/topic/portfolio` via `LivePushService`. Fully unit-tested by driving ticks directly — no network in tests.
- **After-hours:** `MarketClock.isRegularHours(Instant)` (America/New_York 09:30–16:00, weekdays; holidays not modeled — noted) flags each price; snapshot carries `anyAfterHours`.
- **Price-feed seam:** `PriceFeed` interface + `FinnhubPriceFeed` (JDK `java.net.http.WebSocket`, Jackson-3 parse) **gated by `@ConditionalOnProperty("argus.finnhub.api-key")`** — absent without a key, so dev/test/no-key contexts open no socket. `PriceFeedStarter` (portfolio side, `ObjectProvider<PriceFeed>`) wires the feed to the engine on `ApplicationReadyEvent`, avoiding a marketdata↔portfolio bean cycle. **Activation:** set `argus.finnhub.api-key` (deliberately not bound in committed config — an empty value would falsely activate the condition; the Mini/laptop sets it from the real key). The live WS round-trip is the only piece needing a key — validate on the laptop dev key or the Mini.
- **Totals consistency:** a position joins the CAD totals only once priced, so `totalPnl = value − cost` stays coherent while prices stream in.
- **No DB change** (prices are transient); next free Flyway version stays **V8**. Reused `LivePushService`/`WebSocketConfig` (1.6) and `AnimatedNumber`/`wsClient` (1.7) unchanged.
- **Scope honored:** full sortable holdings table → 3.5; chart → 3.6; day P&L (needs prev-close) → 3.5; per-tick FX / holiday calendar / Finnhub FX poller → out.

### File List

**New (backend):** `marketdata/MarketClock.java`, `marketdata/PriceFeed.java`, `marketdata/FinnhubPriceFeed.java`, `portfolio/LivePortfolioService.java`, `portfolio/PortfolioSnapshot.java`, `portfolio/PositionValue.java`, `portfolio/PriceFeedStarter.java`, `portfolio/PortfolioValueController.java`
**New (backend tests):** `marketdata/MarketClockTest.java`, `portfolio/LivePortfolioServiceTest.java`, `portfolio/PortfolioValueIntegrationTest.java`
**New (frontend):** `src/features/portfolio/PortfolioValue.tsx`
**Modified (frontend):** `src/lib/apiClient.ts` (snapshot types + `getPortfolioValue`), `src/app/(dashboard)/portfolio/page.tsx` (live value card)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story created (create-story workflow). Builds on 3.1–3.3: live value/P&L engine + STOMP push behind a key-gated Finnhub price-feed seam; full table/chart/day-P&L deferred to 3.5/3.6. Status → ready-for-dev. |
| 2026-06-22 | Decisions confirmed: engine+seam (feed key-gated), CAD via BoC FxRateService, day P&L → 3.5. |
| 2026-06-22 | Implemented real-time value (FR-2): `LivePortfolioService` + `PortfolioSnapshot`/`PositionValue`, `MarketClock` after-hours, key-gated `FinnhubPriceFeed` behind a `PriceFeed` seam wired by `PriceFeedStarter`, `GET /api/portfolio/value`, live `PortfolioValue` UI (AnimatedNumber + STOMP). 125 backend tests (+9) green; frontend lint+build clean. Status → review. |


## Code Review (2026-06-23, Epic-3 batch)

Reviewed in the combined 3.4–3.9 adversarial batch review (Blind + Edge + Acceptance Auditor, Opus 4.8). **0 High AC violations**; verdict pass. Fixes applied in-batch and deferrals logged in `deferred-work.md`. Status → done.
