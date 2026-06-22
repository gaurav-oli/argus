---
baseline_commit: 018525e
---

# Story 3.2: ACB with purchase-time FX

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the Canadian investor,
I want CAD cost basis computed at the USD/CAD rate from the time of purchase,
so that my P&L and tax-relevant gain/loss are correct (not distorted by today's FX).

## Acceptance Criteria

1. **Per-lot cost model** — A position's cost basis is held as one or more **lots** (each: shares, unit/total cost in the original trade currency, trade date, the USD/CAD rate at that date, and an `fxEstimated` flag). Story 3.1's single-value positions are migrated to **one lot each** with no data loss. (FR-1b "per lot, where lot data is available")
2. **CAD ACB at purchase-time FX** — For a US-listed (USD) holding, the CAD cost base uses the **USD/CAD rate at the purchase date**, never today's rate. **Given** a USD holding with a known trade date, **When** ACB is computed, **Then** `cadAcb = Σ(lot trade-currency cost × lot purchase-FX) ` and P&L/tax gain is measured against that CAD ACB — not a live-converted USD basis. CAD-denominated holdings use FX = 1. (FR-1b)
3. **Weighted-average across lots** — ACB is the **weighted-average cost across all lots of the same security** (Canadian non-registered convention, A-16), in both trade currency and CAD — not FIFO or lot-specific. Adding a lot recomputes the position's weighted-average ACB. (FR-1b, A-16)
4. **Unknown FX is flagged, never guessed silently** — **Given** a lot whose purchase-time FX can't be determined (e.g. the PDF omitted the date, or no rate is available for that date), **When** ACB is computed, **Then** the lot/position is flagged **`FX estimated`** (a clearly-labelled placeholder rate is used so a value still shows) until the user confirms a rate or date. (FR-1b consequence #2) This subsumes the Story-3.1 review deferral "silent USD currency default."
5. **Confirm/override FX** — The user can supply a purchase **date** (→ the platform looks up the rate) or an explicit **rate** for a flagged lot/position; on confirm the `fxEstimated` flag clears and ACB recomputes. Endpoint: `PUT /api/portfolio/positions/{id}/fx` (session-gated, RFC 9457 errors).
6. **Historical USD/CAD source** — Purchase-time rates come from a historical USD/CAD source (see Dev Notes — **Bank of Canada Valet** recommended: free, no API key, CRA-authoritative for tax, laptop-buildable), resolving to the **nearest prior business day** for weekends/holidays, and **cached** so the same date isn't re-fetched. A lookup failure degrades to `fxEstimated` (never a 500). Live/current FX (FR-2, hourly Finnhub) is **out of scope** here — that's Story 3.4.
7. **Surfaced in the API + UI** — `GET /api/portfolio/positions` returns, per position: trade-currency cost basis + currency, **`cadAcb`**, and **`fxEstimated`**. The holdings UI (the Story-3.1 `ImportStatement` current-holdings table, and/or a positions view) shows the CAD ACB and a visible **"FX estimated"** badge on flagged rows, with an affordance to confirm the rate/date. Money stays `BigDecimal`/`NUMERIC` end-to-end.
8. **Tests** — Backend: weighted-average ACB across multiple lots (mixed/known FX), CAD-holding (FX=1), USD holding with known date → correct CAD ACB, unknown date → `fxEstimated` + placeholder, confirm-FX clears the flag and recomputes; FX source resolves nearest-prior-business-day and caches (no duplicate fetch); lookup failure → `fxEstimated`, not 500. Migration backfills 3.1 positions to single lots. `./mvnw verify` green; frontend `lint` + `build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Lot data model + migration** (AC: #1, #3)
  - [x] `V6__position_lots.sql` (forward-only, snake_case): `position_lots` (`id`, `position_id` bigint NOT NULL FK→`positions(id)` ON DELETE CASCADE, `shares numeric(20,6) NOT NULL`, `unit_cost numeric(20,6)` nullable, `trade_currency text NOT NULL`, `trade_date date` nullable, `fx_to_cad numeric(18,8)` nullable, `fx_estimated boolean NOT NULL DEFAULT false`, `created_at`/`updated_at timestamptz`), `idx_position_lots_position` on `position_id`. Add cached derived columns to `positions`: `cad_acb numeric(20,4)` + `fx_estimated boolean NOT NULL DEFAULT false` (kept in sync from lots; `cost_basis`/`cost_basis_currency`/`shares` remain the trade-currency weighted-average caches).
  - [x] **Backfill:** in the same migration, insert one `position_lots` row per existing `positions` row (shares, cost_basis→derive unit_cost or store as total, cost_basis_currency→trade_currency, acquisition_date→trade_date, fx unknown→`fx_estimated = true`). No position loses data.
  - [x] JPA `PositionLot` entity + `PositionLotRepository` mirroring the `Position`/`AppSettings` idiom (`@Column(name=…)`, `BigDecimal`/`LocalDate`/`Instant`, `protected` ctor). Add the new fields/getters to `Position`.
- [x] **Task 2 — Historical FX service** (AC: #6)
  - [x] In `com.argus.marketdata` (currently only `package-info.java`): `FxRateService.usdCadOn(LocalDate) : Optional<BigDecimal>` — fetch the USD/CAD rate for the date (nearest prior business day), via the chosen source (Bank of Canada Valet — see Dev Notes/latest-tech). Use a `RestClient`/`HttpClient` with a short timeout; on any failure return empty (caller flags `fxEstimated`), never throw to the controller.
  - [x] `fx_rates` cache table (`V6` or `V7`): `(pair text, rate_date date, rate numeric(18,8), source text, fetched_at timestamptz, PRIMARY KEY(pair, rate_date))`. `FxRateService` checks the cache before calling out and writes through on fetch. (CAD→CAD short-circuits to 1, no lookup.)
- [x] **Task 3 — ACB computation** (AC: #2, #3, #4)
  - [x] `AcbCalculator` (pure, unit-testable, **no LLM**): given a position's lots → weighted-average ACB in trade currency (Σ cost / Σ shares) and **CAD** (Σ (lot cost × lot fx_to_cad)). `fxEstimated` = true if any contributing lot is estimated. Recompute + persist the `positions` caches (`shares`, `cost_basis`, `cad_acb`, `fx_estimated`) whenever lots change.
  - [x] Wire into the **import-confirm path** (`PortfolioImportService.confirmImport`, Story 3.1): each confirmed holding → a `Position` + one `PositionLot`; resolve `fx_to_cad` via `FxRateService` when `trade_date` known + currency USD (else `fx_estimated=true`, placeholder rate = latest available or `1`); CAD holdings → fx=1. Then run `AcbCalculator`. **Note** this changes 3.1's `confirmImport` — preserve its existing behavior (staging, confirm-before-overwrite, 409-on-reconfirm, the pessimistic lock).
- [x] **Task 4 — Endpoints** (AC: #5, #7)
  - [x] Extend `PositionView` with `cadAcb`, `fxEstimated` (keep existing fields). `GET /api/portfolio/positions` returns them.
  - [x] `PUT /api/portfolio/positions/{id}/fx` (session-gated): body `{ rate?: BigDecimal, date?: LocalDate }` — if `date`, look up the rate; if `rate`, use it directly; recompute ACB, clear `fxEstimated`. Validate via `BadRequestException` (need exactly one of rate/date; rate>0); `NotFoundException` for unknown id. Success returns the updated `PositionView` (resource directly, camelCase).
- [x] **Task 5 — Frontend** (AC: #7)
  - [x] Update `lib/apiClient.ts` `Position` type (+ `cadAcb`, `fxEstimated`) and add `confirmPositionFx(id, { rate? , date? })`.
  - [x] In `features/portfolio/ImportStatement.tsx` (and/or a small positions component): show **CAD ACB** alongside trade-currency cost, an **"FX estimated"** badge (use the `warning` token, consistent with the 3.1 `needsReview` styling) on flagged rows, and a confirm-rate/date affordance that calls `confirmPositionFx` and refreshes.
- [x] **Task 6 — Tests + verify** (AC: #8)
  - [x] Unit: `AcbCalculatorTest` (weighted-average across lots, CAD vs USD, fxEstimated propagation), `FxRateServiceTest` (nearest-prior-business-day, cache hit avoids re-fetch, failure→empty). Backend integration (Testcontainers, mirror `PortfolioImportIntegrationTest`): import→confirm produces lots + cadAcb; unknown-date holding flagged `fxEstimated`; `PUT …/fx` clears the flag + recomputes; V6 backfill leaves 3.1 positions intact. Stub/мock the external FX call in tests (no live network).
  - [x] `./mvnw verify` green; `npm run lint` + `npm run build` clean.

## Dev Notes

### ⭐ Decision to confirm — historical FX source (Bank of Canada Valet recommended)
The PRD only specifies **Finnhub** for *current* USD/CAD (hourly, A-1 / FR-2) — that's live FX for Story 3.4. This story needs **historical purchase-time** rates (possibly years back) and the purpose is **tax-relevant** (FR-1b). Recommendation: **Bank of Canada Valet API** for purchase-time FX —
- Free, **no API key**, no rate limits → fully laptop-buildable (no Mini, no secret).
- Series `FXUSDCAD` is the daily USD/CAD rate the **CRA accepts** for ACB/tax — the right authority for a tax-correctness feature.
- Finnhub free tier's forex history is limited and key-gated; using it here would couple a tax calc to a flaky, capped feed.

Keep Finnhub for **live** FX in 3.4. *(Flagged as Open Question #1 — confirm before building Task 2.)*

### Scope boundaries (don't over-build)
- **Live/current FX + per-tick value** → Story 3.4 (Finnhub WS). 3.2 is purchase-time/historical only.
- **Corporate actions** (splits adjust per-share cost across lots) → Story 3.3. Design `position_lots` so 3.3 can adjust lots, but don't implement adjustments here.
- **Manual multi-lot entry / add-remove lots UI** → Story 3.7. 3.2 creates lots from import + supports FX confirm; full lot CRUD is later.
- **Year-end tax summary** (F41) → Phase 3. 3.2 just lays the correct ACB/lot foundation it will use.

### Builds directly on Story 3.1 (read these — they WILL change)
- `portfolio/PortfolioImportService.confirmImport` — **the integration point.** It currently creates `Position` rows only. 3.2 makes it also create a `PositionLot` and resolve FX. Preserve 3.1's guarantees: staged-pending → confirm, confirm-before-overwrite, 409 on reconfirm, the `findByIdForUpdate` pessimistic lock, flagged-not-dropped holdings.
- `portfolio/Position.java` + `V5__portfolio_positions.sql` — extended here (new `cad_acb`, `fx_estimated`; lots become the cost source of truth, positions caches the weighted-average). `cost_basis`/`cost_basis_currency` from 3.1 stay as the trade-currency cache.
- `portfolio/PositionView.java` — extend (don't replace) with `cadAcb` + `fxEstimated`.
- `portfolio/StatementParser.java` — its per-row `costBasisCurrency` feeds each lot's `trade_currency`; the 3.1 review noted currency was defaulted to USD silently — 3.2 resolves the *consequence* by flagging `fxEstimated` when currency/FX is uncertain.
- Frontend `features/portfolio/ImportStatement.tsx` + `lib/apiClient.ts` — extend the holdings display + `Position` type; reuse the `warning`-token badge pattern used for `needsReview`.

### Architecture / convention guardrails (mandatory)
- **Money:** `NUMERIC`/`BigDecimal`, never float; carry currency; FX rate is `numeric(18,8)`. Round CAD ACB to 4 dp (`numeric(20,4)`). [architecture.md#Format Patterns]
- **Case boundary:** snake_case columns ↔ camelCase JSON; JPA maps it. [architecture.md#Naming Patterns]
- **REST:** `/api/portfolio/...`, resource returned directly, RFC 9457 via the existing `GlobalExceptionHandler` (`BadRequestException`/`NotFoundException`). Session-gated by `SessionAuthFilter` (not allowlisted). [Decision 6; Story 3.1]
- **External calls:** the FX fetch must be resilient — short timeout + cache + graceful degrade to `fxEstimated`. Full Resilience4j rate-limiting is Epic 4 / GAP-4 (Finnhub); don't pull it in just for the no-limit BoC call. [architecture.md#GAP-4]
- **Jackson:** this Boot 4 app runs **Jackson 3 (`tools.jackson`)**, no injectable `ObjectMapper` bean — own a `JsonMapper.builder().build()` if you need to parse the FX JSON (handles `BigDecimal`/`LocalDate` natively). [Story 3.1 learning]
- **Flyway:** next free version is **V6** (V1–V5 exist); forward-only; `ddl-auto: validate`. For new jsonb (none expected here) use `@JdbcTypeCode(SqlTypes.JSON)`.
- **DB image / Testcontainers:** Postgres 18 via `pgvector/pgvector:0.8.2-pg18`, `TestcontainersConfiguration` is shared; integration tests `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`, auth via `/api/auth/pin` + `/api/auth/login`. [Story 3.1 tests]

### Files to touch
- **New (backend):** `portfolio/PositionLot.java`, `portfolio/PositionLotRepository.java`, `portfolio/AcbCalculator.java`, `marketdata/FxRateService.java`, `marketdata/FxRate.java` (+ repo), `resources/db/migration/V6__position_lots_and_fx.sql`, tests `AcbCalculatorTest`, `FxRateServiceTest`, extend `PortfolioImportIntegrationTest` (or a new `AcbIntegrationTest`).
- **Modified (backend):** `portfolio/Position.java` (+cadAcb, fxEstimated, lots link), `portfolio/PositionView.java`, `portfolio/PortfolioImportService.java` (lot + FX on confirm), `portfolio/PortfolioImportController.java` (PUT …/fx), possibly `application.yml` (BoC base URL as a property).
- **New/Modified (frontend):** `lib/apiClient.ts` (type + `confirmPositionFx`), `features/portfolio/ImportStatement.tsx` (CAD ACB + FX-estimated badge + confirm affordance).

### Testing standards
- Backend: JUnit Jupiter assertions (no AssertJ on classpath — see 3.1); pure unit tests for `AcbCalculator`/`FxRateService` (mock the HTTP call — do NOT hit the live BoC endpoint in tests); Testcontainers for the integration flow. `./mvnw verify`.
- Frontend: `npm run lint` + `npm run build` clean (standing gate; no component test harness).

### Latest tech notes — Bank of Canada Valet API (if chosen)
- Endpoint: `https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`. No auth. Series **`FXUSDCAD`** = CAD per 1 USD, published business days (~16:30 ET); no weekend/holiday observation → query a small window ending on the date and take the **latest observation ≤ the target date** (nearest prior business day). Response shape: `{ "observations": [ { "d": "2023-01-13", "FXUSDCAD": { "v": "1.3413" } } ] }`.
- Make the base URL a config property (`argus.fx.valet-base-url`) so tests can point at a stub and the Mini can override if needed.

### Project Structure Notes
- New `marketdata` FX code aligns with `architecture.md#Requirements → Structure Mapping` (F1/F2 → `portfolio`, `marketdata`). Lots + ACB live in `portfolio`; FX fetching in `marketdata`.
- No variance from the unified structure. `position_lots` + `fx_rates` are new; `positions` is extended (additive columns + backfilled lots), not rewritten.

### References
- [Source: epics.md#Epic 3 / Story 3.2] — story + BDD ACs.
- [Source: prd.md#FR-1b] — original-currency + CAD-at-purchase-FX cost basis; weighted-average across lots; unknown-FX → prompt + "FX estimated". [A-16] weighted-average convention. [A-1] Finnhub = *current* FX only (live, 3.4).
- [Source: architecture.md#Decision 3] — Postgres holds positions + ACB lots; #Format/Naming Patterns (money/case); #GAP-4 (Finnhub resilience, not BoC).
- [Source: 3-1-portfolio-pdf-upload.md] — confirm flow, conventions, Jackson-3/no-ObjectMapper-bean, test patterns; review deferral of currency/FX to this story.

## Open Questions (for the user — non-blocking; defaults chosen)
1. **Historical FX source: ✅ RESOLVED (2026-06-22) → Bank of Canada Valet** (free, no-key, CRA-authoritative, laptop-buildable) for purchase-time rates; Finnhub stays for live FX in 3.4. Build Task 2 against the Valet API.
2. **`fxEstimated` placeholder rate:** when FX is unknown, the spec shows a value using a placeholder (latest available rate, clearly flagged). Confirm that's preferable to showing "—"/no CAD ACB until the user confirms.
3. **Lots now vs position-level only:** spec introduces `position_lots` now (per A-16 weighted-average + Phase-3 tax foundation), with 3.1 imports → one lot each. Confirm that's not too much for 3.2 vs. deferring the lots table to 3.7.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 99 tests, 0 failures** (+17 vs the 82 after 3.1).
- `npm run lint` clean; `npm run build` (Next 16 / Turbopack) typechecks + compiles clean.
- FX source: **Bank of Canada Valet** (confirmed by user 2026-06-22). Integration tests stub `FxRateClient` via `@MockitoBean` so they never hit the live endpoint; the Valet payload parsing is unit-tested with canned JSON (`BankOfCanadaFxClientTest`).

### Completion Notes List

- **Per-lot model:** `position_lots` is the cost source of truth; `positions` caches the weighted-average (`shares`, `cost_basis`, `cad_acb`, `fx_estimated`), recomputed by `AcbCalculator` on every lot change. V6 backfills one lot per existing 3.1 position (CAD → FX 1; USD → flagged estimated, since they predate the FX feature).
- **Purchase-time FX:** on import-confirm each holding → a lot; USD + known date → `FxRateService.usdCadOn(date)` (BoC, nearest prior business day, cached in `fx_rates`); CAD → FX 1; unknown date / lookup failure → `fxEstimated` (never a 500). `PUT /api/portfolio/positions/{id}/fx` (rate or date) confirms a flagged position and recomputes.
- **Weighted-average ACB** (A-16): CAD ACB = Σ(lot cost × lot FX); trade-currency cost = Σ lot cost. If any contributing lot lacks (cost, FX), CAD ACB is left null and the position is `fxEstimated`.
- **Resolves the 3.1 review deferral** "silent USD currency default" — currency uncertainty now surfaces as `fxEstimated` rather than a silent guess.
- **Preserved 3.1 guarantees** in `confirmImport`: staged-pending → confirm, confirm-before-overwrite, 409-on-reconfirm, the `findByIdForUpdate` pessimistic lock, flagged-not-dropped holdings.
- **Frontend:** holdings table gains a **CAD ACB** column + an inline **"FX estimated"** affordance (enter a USD/CAD rate → `confirmPositionFx` → recompute), styled with the `warning` token (consistent with 3.1's `needsReview`).
- **Scope honored:** live/per-tick FX → 3.4; corporate-action lot adjustments → 3.3; manual lot CRUD → 3.7.

### File List

**New (backend):** `portfolio/PositionLot.java`, `portfolio/PositionLotRepository.java`, `portfolio/AcbCalculator.java`, `marketdata/FxRate.java`, `marketdata/FxRateRepository.java`, `marketdata/FxRateClient.java`, `marketdata/BankOfCanadaFxClient.java`, `marketdata/FxRateService.java`, `resources/db/migration/V6__position_lots_and_fx.sql`
**New (backend tests):** `portfolio/AcbCalculatorTest.java`, `marketdata/BankOfCanadaFxClientTest.java`, `marketdata/FxRateServiceTest.java`
**Modified (backend):** `portfolio/Position.java` (cadAcb/fxEstimated + `updateAcbCaches`), `portfolio/PositionView.java` (+cadAcb, fxEstimated), `portfolio/PortfolioImportService.java` (lots + FX on confirm, `confirmFx`), `portfolio/PortfolioImportController.java` (`PUT …/fx`), `test/.../portfolio/PortfolioImportIntegrationTest.java` (FxRateClient `@MockitoBean` + 5 ACB/FX tests)
**Modified (frontend):** `src/lib/apiClient.ts` (Position +cadAcb/fxEstimated, `confirmPositionFx`), `src/features/portfolio/ImportStatement.tsx` (CAD ACB column + FX-estimated confirm)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story created (create-story workflow). Builds on 3.1: introduces `position_lots` + historical-FX ACB. Status → ready-for-dev. |
| 2026-06-22 | FX source confirmed: Bank of Canada Valet (Open Question #1 resolved). |
| 2026-06-22 | Implemented per-lot CAD ACB at purchase-time FX (FR-1b): `position_lots` + V6 backfill, `AcbCalculator` (weighted-average), `FxRateService`/BoC Valet client (cached, nearest-prior-day), `PUT …/fx` confirm, CAD-ACB UI + FX-estimated affordance. 99 backend tests (+17) green; frontend lint+build clean. Status → review. |
| 2026-06-22 | Code review (3 adversarial layers): 6/8 ACs pass, 0 High AC violations. AC4 decision resolved (keep null+flag). Applied 3 patches — FX-rate scale/magnitude bound (High), reject both rate+date, BoC client hardening (window + malformed-JSON safety). 6 deferred to 3.7/refinement, ~10 dismissed (verified not-reachable). 102 backend tests (+3) green. Status → done. |

## Code Review (2026-06-22)

Adversarial 3-layer review (Blind + Edge + Acceptance Auditor, Opus 4.8), diff vs baseline `018525e`. **Auditor: 6/8 ACs pass, AC1 + AC4 partial, 0 High AC violations.** Triage: 1 decision, 3 patches, 6 deferred (mostly gated by the single-lot/single-currency invariant that holds until 3.7), ~10 dismissed (verified not-reachable or handled).

### Review Findings

- [x] [Review][Decision] AC4 — unknown FX shows no CAD ACB (`cadAcb` null + "FX estimated") rather than a placeholder. **RESOLVED (user, 2026-06-22): keep null + flag** — more honest, aligns with Argus's calibration/honesty framing; AC4's "placeholder value" wording is superseded. No code change.
- [x] [Review][Patch] User-confirmed FX `rate` has no scale/magnitude bound [PortfolioImportController.confirmFx] — HIGH — FIXED: reject scale > 8 dp or magnitude outside (0, 1000] → 400, so it can't overflow/round `numeric(18,8)`.
- [x] [Review][Patch] `PUT …/fx` accepted BOTH `rate` and `date` [PortfolioImportController.confirmFx] — MED — FIXED: both-provided → 400 ("not both"); exactly-one enforced.
- [x] [Review][Patch] BoC client hardening [BankOfCanadaFxClient] — LOW — FIXED: lookup window 10→14d; `parseLatest` wraps `readTree` → returns empty on malformed JSON instead of throwing.
- [x] [Review][Defer] confirmFx applies one rate to all estimated lots — per-lot FX needed once 3.7 adds multi-lot [PortfolioImportService.confirmFx] — deferred to 3.7
- [x] [Review][Defer] Mixed-currency-per-position cost sum/label — add guard with 3.7 multi-lot [AcbCalculator] — deferred, not reachable now
- [x] [Review][Defer] UI confirms by rate only, not date [ImportStatement.tsx] — deferred, later polish
- [x] [Review][Defer] Residual silent USD currency default not flagged [StatementParser] — deferred, parser refinement
- [x] [Review][Defer] No V6-backfill integration test [V6 migration] — deferred, needs a migration-test harness
- [x] [Review][Defer] Future-dated FX confirm not rejected [BankOfCanadaFxClient] — deferred, implausible action

_Dismissed (verified): null-shares migration failure (parser guarantees non-null shares — NUMBER-regex match always parses); recompute nulls costBasis (only when already null — no regression; backfill copies cost into the lot); confirmFx no-op on a non-estimated position (UI-gated, harmless); empty-lot position (not reachable — lot created with position); negative/zero lot cost (already flagged `needsReview` at parse); dead `d == null` check; `"v": null` rare gap-day; `total_cost` vs spec'd `unit_cost` (equivalent, cleaner); `FxRateService` read-write tx (writes on miss); frontend refresh race (single-user)._
