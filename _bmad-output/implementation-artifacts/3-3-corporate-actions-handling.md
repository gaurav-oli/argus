---
baseline_commit: 1158426
---

# Story 3.3: Corporate-actions handling

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want stock splits, ticker changes, and mergers applied to my holdings automatically (or flagged when ambiguous),
so that my share counts and cost basis stay correct instead of silently breaking on the first corporate action.

## Acceptance Criteria

1. **Split / reverse-split preserves total cost basis** — **Given** a split (e.g. 2:1) on a held position, **When** it is applied, **Then** each lot's share count scales by the ratio while its **total cost is unchanged** (so per-share cost adjusts and total cost basis + CAD ACB are preserved), the position's cached aggregates recompute, **And** an in-app notice records the adjustment. Reverse splits (e.g. 1:10 → ratio 0.1) work the same way. (FR-1c)
2. **Ticker change / merger re-maps the position** — **Given** a ticker symbol change (or a simple share-ratio merger), **When** applied, **Then** the position's ticker is updated to the new symbol and the old→new mapping is recorded (so later epics can link news/prices/recommendations across symbols). (FR-1c) Cross-symbol history *linking* of news/prices/recs is out of scope until those data exist (Epics 4/6) — record the alias now.
3. **Ambiguous actions never corrupt — they wait for confirmation** — **Given** a corporate action that can't be applied unambiguously (no matching held position, multiple matches, a merger with a cash component or unknown exchange ratio, or a missing ratio), **When** processed, **Then** it is stored as **`pending` (🟡 Important)** for manual confirmation **And** the position is left untouched — never silently mutated. (FR-1c — the load-bearing safety criterion)
4. **Auto-apply vs flag policy** — An action is **auto-applied** only when it is unambiguous: exactly one matching held position (by ticker), a known positive ratio, and a type in {split, reverse_split, ticker_change}. Everything else → `pending`. Special/stock dividends and mergers default to `pending` (manual confirm) in this story.
5. **Manual entry + confirm/dismiss** — `POST /api/portfolio/corporate-actions` records an action (manual entry / the detection seam); `GET /api/portfolio/corporate-actions` lists them with status; `POST …/{id}/confirm` applies a pending action; `POST …/{id}/dismiss` discards it. All session-gated, RFC 9457 errors, money/shares as `BigDecimal`/`NUMERIC`. Re-confirming or confirming a non-pending action → 409.
6. **Detection seam (auto-detection deferred)** — A `CorporateActionDetector` interface exists so a Finnhub-backed auto-detector can be added later; **automatic detection (scheduled polling of Finnhub `/stock/split` etc.) is OUT of scope here** (it's a scheduled-agent concern, and depends on the Finnhub feed) — see Dev Notes. This story delivers the application engine + the manual/confirm path that the AC's "manual confirmation" branch requires.
7. **Frontend** — The portfolio screen shows a **Corporate actions** list: applied actions (with what changed) and `pending` ones visually flagged (🟡 `warning` token) with **Confirm** / **Dismiss** controls. After a split is applied/confirmed, the holdings table reflects the new share counts and the unchanged total cost / CAD ACB.
8. **Tests** — Backend unit: split preserves total cost + scales shares across multiple lots, reverse split, ticker change re-maps + records alias, ambiguous (no match / multi-match / merger / missing ratio) → pending and position untouched, confirm applies + recomputes ACB, dismiss. Integration (Testcontainers, mirror `PortfolioImportIntegrationTest`): manual record → auto-apply a clean split → `GET positions` shows scaled shares + preserved cost/CAD ACB; ambiguous → pending, position unchanged; confirm/dismiss; 409 on re-confirm; session-gating. `./mvnw verify` green; frontend `lint` + `build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Corporate-action domain + migration** (AC: #1, #2, #5)
  - [x] `V7__corporate_actions.sql` (forward-only, snake_case): `corporate_actions` (`id`, `ticker text NOT NULL`, `position_id bigint` nullable FK→`positions(id) ON DELETE SET NULL`, `type text NOT NULL` — `split|reverse_split|stock_dividend|ticker_change|merger`, `ratio numeric(20,8)` nullable, `new_ticker text` nullable, `ex_date date` nullable, `status text NOT NULL DEFAULT 'pending'` — `pending|applied|dismissed`, `note text`, `source text NOT NULL DEFAULT 'manual'` — `manual|finnhub`, `created_at timestamptz`, `applied_at timestamptz`), `idx_corporate_actions_status` on `status`.
  - [x] `CorporateAction` entity + `CorporateActionType` enum + `CorporateActionRepository` (mirror the `Position`/`PositionLot` idiom: `@Column(name=…)`, `BigDecimal`/`LocalDate`/`Instant`, `protected` ctor, mutators like `markApplied()`/`markDismissed()`).
- [x] **Task 2 — Extract shared ACB recompute** (AC: #1)
  - [x] Extract the private `recomputeAcb` from `PortfolioImportService` into a small shared component (e.g. `PositionAcbService.recompute(Position)` using `AcbCalculator` + `PositionLotRepository` + `PositionRepository`); refactor `PortfolioImportService` to depend on it. This keeps the split/confirm path and the import path using ONE recompute implementation (no divergence).
  - [x] Add entity mutators: `PositionLot.applySplit(BigDecimal ratio)` (shares ×= ratio, **total_cost unchanged**, bump `updatedAt`); `Position.applyTickerChange(String newTicker)`.
- [x] **Task 3 — Application engine** (AC: #1, #2, #3, #4)
  - [x] `CorporateActionService`:
    - `record(request)` → match held position(s) by `ticker`; decide **auto-apply** (unambiguous per AC#4) vs **pending**; persist the action; if auto-apply, apply immediately + `markApplied()`.
    - `apply(action, position)`: **split/reverse** → each lot `applySplit(ratio)` then `PositionAcbService.recompute` (total cost + CAD ACB preserved, shares scaled); **ticker_change** → `position.applyTickerChange(newTicker)` + keep the old→new on the action row (alias record); **merger (simple ratio)** → treat as split-then-rename **only if** a clean positive ratio + new_ticker and exactly one match, else pending.
    - `confirm(id)` → load `pending` (else 409/404), apply, `markApplied()`. `dismiss(id)` → `markDismissed()`.
  - [x] **Never mutate on the pending path** — ambiguous actions persist with status `pending` and touch no position/lot until confirmed.
- [x] **Task 4 — Endpoints** (AC: #5)
  - [x] On `PortfolioImportController` (or a new `CorporateActionController` in `com.argus.portfolio`, session-gated under `/api/portfolio`): `POST /corporate-actions`, `GET /corporate-actions`, `POST /corporate-actions/{id}/confirm`, `POST /corporate-actions/{id}/dismiss`. Validate type/ratio/new_ticker via `BadRequestException`; `NotFoundException` unknown id; `ConflictException` (409) confirming a non-pending action. Return `CorporateActionView` records (camelCase, resource directly).
- [x] **Task 5 — Detection seam (no auto-detect)** (AC: #6)
  - [x] Define `CorporateActionDetector` interface (`List<DetectedAction> detectFor(Collection<String> tickers)`). Do **not** implement Finnhub polling here (deferred — Dev Notes). The manual `POST /corporate-actions` is the entry point now; a future detector feeds the same `CorporateActionService.record`.
- [x] **Task 6 — Frontend** (AC: #7)
  - [x] `lib/apiClient.ts`: `CorporateAction` type + `recordCorporateAction` / `listCorporateActions` / `confirmCorporateAction` / `dismissCorporateAction`.
  - [x] `features/portfolio/CorporateActions.tsx`: list applied + `pending` (🟡 `warning` token) actions with Confirm/Dismiss on pending; surface in `app/(dashboard)/portfolio/page.tsx` near the holdings. After apply/confirm, refresh holdings (shares reflect the split). Keep it consistent with `ImportStatement.tsx` patterns (plain React + apiClient + `ApiError`).
- [x] **Task 7 — Tests + verify** (AC: #8)
  - [x] Unit (`CorporateActionServiceTest` or split a calculator out): split preserves total cost across 2 lots + scales shares; reverse split; ticker change re-maps + records alias; ambiguous (no match / multi-match / merger / null ratio) → pending + position untouched; confirm applies + ACB recomputes; dismiss.
  - [x] Integration (mirror `PortfolioImportIntegrationTest`, reuse the `@MockitoBean FxRateClient` setup so confirm/import FX is stubbed): import a holding → record a clean 2:1 split → auto-applied → `GET positions` shows doubled shares, **unchanged** `costBasis` + `cadAcb`; ambiguous action → `pending`, position unchanged; confirm/dismiss; 409 on re-confirm; endpoints require a session.
  - [x] `./mvnw verify` green; `npm run lint` + `npm run build` clean.

## Dev Notes

### Why auto-detection is deferred (scope decision)
FR-1c assumes corporate-action data "sourced from Finnhub." Automatic detection means a **scheduled poll** of Finnhub (`/stock/split`, symbol-change feeds) over held tickers — that is agent/scheduler infrastructure (Epic 4 territory) and depends on the Finnhub feed/tier. This story delivers the **application engine + the manual-confirmation path** that FR-1c's "🟡 manual confirmation" branch explicitly requires, plus a `CorporateActionDetector` seam a Finnhub-backed detector plugs into later. This keeps 3.3 deterministic, fully laptop-buildable, and unit-testable — same pattern as 3.2 (engine + client seam, real feed later). *(Open Question #1 — confirm.)*

### The split math (the core correctness point)
Because Story 3.2 made **lots** the cost source of truth and stores **`total_cost` per lot** (not unit cost), a split is clean: **multiply each lot's `shares` by the ratio and leave `total_cost` unchanged.** Total cost basis is therefore preserved by construction, per-share cost falls out as `total_cost / shares`, and CAD ACB (Σ lot_cost × lot_fx) is unchanged. Then run the shared recompute so `positions.shares` / `cost_basis` / `cad_acb` caches update. This is exactly FR-1c's "share count and per-share cost basis adjusted so total cost basis … remains correct." Reverse split = ratio < 1.

### Scope boundaries (don't over-build)
- **No automatic Finnhub detection / scheduled polling** → seam only (this story); detector impl later.
- **No cross-symbol history linking** (news/prices/recommendations across old/new ticker) → those data don't exist until Epics 4/6; just record the alias on the action row now.
- **Mergers with cash components / complex exchange ratios** → `pending` for manual confirmation, not auto-applied (don't model cash-in-lieu / fractional-share payouts here).
- **Web Push / alert-fatigue** for the "notified" / 🟡 alert → Epic 8. Here "notified"/"🟡" = an in-app corporate-actions list with status; do NOT build push infrastructure.
- **Manual lot CRUD** stays Story 3.7.

### Builds on 3.1 + 3.2 (read these — some change)
- `portfolio/PortfolioImportService.java` — **extract** its private `recomputeAcb` into the shared `PositionAcbService` and have both import and corporate-actions use it. Preserve all 3.1/3.2 behavior (staging→confirm, confirm-before-overwrite, 409-on-reconfirm, `findByIdForUpdate` lock, lot+FX on confirm).
- `portfolio/Position.java` — add `applyTickerChange(newTicker)`; `shares`/`cost_basis`/`cad_acb` remain caches updated via `updateAcbCaches` (do not set them directly from the split path — go through recompute).
- `portfolio/PositionLot.java` — add `applySplit(ratio)` (shares ×= ratio, total_cost untouched). Lots are the source of truth; the split mutates lots, then recompute.
- `portfolio/AcbCalculator.java` — reused unchanged (operates on lots).
- `portfolio/PortfolioImportController.java` / `PositionView.java` — controller may host the new endpoints; `PositionView` already exposes shares/cost/cadAcb so the holdings UI reflects splits with no DTO change.
- Frontend `features/portfolio/ImportStatement.tsx` + `lib/apiClient.ts` — mirror their plain-React + typed-client patterns for the new `CorporateActions.tsx`.

### Architecture / convention guardrails (mandatory)
- **Money/shares:** `NUMERIC`/`BigDecimal`, never float; `ratio numeric(20,8)`. Recompute rounds via `AcbCalculator` (shares 6dp, cost/CAD 4dp, HALF_UP). [architecture.md#Format Patterns]
- **Case boundary** snake_case ↔ camelCase; **REST** under `/api/portfolio`, resource returned directly, RFC 9457 via `GlobalExceptionHandler` (`BadRequestException`/`NotFoundException`/`ConflictException`). Session-gated by `SessionAuthFilter` (not allowlisted). [Decision 6; Stories 3.1/3.2]
- **Flyway:** next free version is **V7** (V1–V6 exist); forward-only; `ddl-auto: validate`.
- **Jackson 3** (`tools.jackson`), no injectable `ObjectMapper` bean — own a `JsonMapper.builder().build()` only if needed. [3.1 learning]
- **Tests:** JUnit Jupiter assertions (no AssertJ); Testcontainers `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`; auth via `/api/auth/pin` + `/api/auth/login`; **reuse `@MockitoBean FxRateClient`** so the import/confirm FX path is stubbed (no live BoC call). Clear `position_lots` → `positions` (+ `corporate_actions`) in `@BeforeEach` mindful of FKs. [3.1/3.2 tests]

### Files to touch
- **New (backend):** `portfolio/CorporateAction.java`, `portfolio/CorporateActionType.java`, `portfolio/CorporateActionRepository.java`, `portfolio/CorporateActionService.java`, `portfolio/CorporateActionView.java`, `portfolio/CorporateActionController.java` (or add to `PortfolioImportController`), `portfolio/PositionAcbService.java`, `portfolio/CorporateActionDetector.java`, `resources/db/migration/V7__corporate_actions.sql`, tests `CorporateActionServiceTest`, integration additions.
- **Modified (backend):** `portfolio/PortfolioImportService.java` (use `PositionAcbService`), `portfolio/Position.java` (+`applyTickerChange`), `portfolio/PositionLot.java` (+`applySplit`).
- **New (frontend):** `src/features/portfolio/CorporateActions.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/app/(dashboard)/portfolio/page.tsx`.

### Testing standards
- Backend `./mvnw verify` (unit + Testcontainers). Frontend `npm run lint` + `npm run build` clean.

### Latest tech notes
- No new external dependency. (A future Finnhub detector would use the existing `RestClient` pattern from `BankOfCanadaFxClient` + the dev Finnhub key; not in this story.) `numeric(20,8)` ratio handles fractional/exchange ratios; validate ratio > 0 for splits.

### Project Structure Notes
- All corporate-action code lives in `com.argus.portfolio` (F1/F2 mapping). `corporate_actions` references `positions`; no existing schema rewritten (additive table + new entity mutators). No structure variance.

### References
- [Source: epics.md#Epic 3 / Story 3.3] — story + BDD ACs.
- [Source: prd.md#FR-1c] — splits adjust shares + per-share cost, total preserved + notified; ticker/merger re-map + link history (deferred); ambiguous → 🟡 manual confirm, never corrupt; [assumption] Finnhub-sourced, SEC-verified in Phase 2.
- [Source: architecture.md#Decision 3 / Format & Naming Patterns] — Postgres positions/lots; money/case/REST rules.
- [Source: 3-1-portfolio-pdf-upload.md, 3-2-acb-with-purchase-time-fx.md] — lots-as-source-of-truth, `AcbCalculator`, recompute, conventions, test patterns, Jackson-3 note.

## Open Questions (for the user — non-blocking; defaults chosen)
1. **Auto-detection scope: ✅ RESOLVED (user, 2026-06-22) → engine + manual/confirm + seam.** Automatic Finnhub detection (scheduled poll) is deferred; build the `CorporateActionDetector` interface only. Manual `POST /corporate-actions` is the entry point this story.
2. **Merger depth:** complex mergers (cash component / unknown exchange ratio) default to `pending` for manual confirmation rather than auto-applied. Confirm that's acceptable for MVP.
3. **Split cost semantics:** total cost basis is preserved (lot shares scale, lot `total_cost` unchanged → per-share cost adjusts). Confirm this matches your intent (vs. adjusting recorded per-share cost some other way).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 111 tests, 0 failures** (+9 vs the 102 after 3.2).
- `npm run lint` clean; `npm run build` (Next 16 / Turbopack) clean.
- Auto-detection deferred per user decision (Open Question #1) → `CorporateActionDetector` seam only; no Finnhub polling.

### Completion Notes List

- **Split math preserves total cost** by construction: `PositionLot.applySplit(ratio)` scales `shares`, leaves `total_cost` untouched; the shared `PositionAcbService.recompute` updates the position caches → `costBasis` + `cadAcb` unchanged, shares scaled (asserted end-to-end).
- **Extracted `PositionAcbService`** from `PortfolioImportService` so the import-confirm path and the corporate-action path share ONE recompute. `PortfolioImportService` now depends on it (no behavior change; all 3.1/3.2 tests still green).
- **Auto-apply vs pending policy:** unambiguous split/reverse-split/ticker-change on exactly one matched position auto-applies; no-match / multi-match / merger / stock-dividend / missing-ratio → `pending` (🟡) with a reason, position untouched (the FR-1c safety criterion). Confirm/dismiss flow with 409 on non-pending.
- **Ticker change** re-maps `position.ticker` and keeps old→new on the action row (cross-symbol history linking deferred — those data don't exist until Epics 4/6). **Merger** always pending; on confirm applies optional share-ratio + re-symbol.
- **Frontend** `CorporateActions.tsx`: record form (type-aware fields) + a list with 🟡 pending Confirm/Dismiss; reloads after an action applies so the holdings table reflects post-split shares.
- **Scope honored:** no automatic Finnhub detection (seam only), no cross-symbol history linking, no cash-component merger modelling, no Web Push (in-app status only), no manual lot CRUD (3.7).

### File List

**New (backend):** `portfolio/CorporateAction.java`, `portfolio/CorporateActionType.java`, `portfolio/CorporateActionRepository.java`, `portfolio/CorporateActionService.java`, `portfolio/CorporateActionView.java`, `portfolio/CorporateActionController.java`, `portfolio/CorporateActionDetector.java`, `portfolio/PositionAcbService.java`, `resources/db/migration/V7__corporate_actions.sql`
**New (backend tests):** `portfolio/PositionLotTest.java`, `portfolio/CorporateActionIntegrationTest.java`
**Modified (backend):** `portfolio/PortfolioImportService.java` (use `PositionAcbService`), `portfolio/Position.java` (+`applyTickerChange`), `portfolio/PositionLot.java` (+`applySplit`), `portfolio/PositionRepository.java` (+`findByTicker`)
**New (frontend):** `src/features/portfolio/CorporateActions.tsx`
**Modified (frontend):** `src/lib/apiClient.ts` (CorporateAction type + 4 fns), `src/app/(dashboard)/portfolio/page.tsx` (CorporateActions card)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story created (create-story workflow). Builds on 3.1/3.2: corporate-action application engine (splits/ticker-change/merger) + manual-confirm path; auto-detection deferred to a seam. Status → ready-for-dev. |
| 2026-06-22 | Auto-detection scope confirmed (Open Question #1): engine + manual/confirm + seam; no Finnhub polling. |
| 2026-06-22 | Implemented corporate-actions handling (FR-1c): `corporate_actions` + V7, `CorporateActionService` (auto-apply unambiguous / pending 🟡 otherwise), split preserves total cost via `applySplit` + extracted `PositionAcbService`, ticker-change re-map, merger confirm, `CorporateActions` UI. 111 backend tests (+9) green; frontend lint+build clean. Status → review. |
| 2026-06-22 | Code review (3 adversarial layers): 0 High AC violations, all 8 ACs pass after fixes. Applied 8 patches — duplicate-ticker collision guard (High), server-side `newTicker` upper-case (High), confirm/dismiss row lock (TOCTOU), merger no-op guard, bounded `applySplit` scale, ratio range/scale validation, multi-lot + multi-match + collision tests, frontend (require ratio/newTicker, hide Confirm for stock dividends, 1:N reverse display). 5 deferred to 3.7/detector, several dismissed (not-reachable/cosmetic). 116 backend tests (+5) green; frontend lint+build clean. Status → done. |

## Code Review (2026-06-22)

Adversarial 3-layer review (Blind + Edge + Acceptance Auditor, Opus 4.8), branch `story/3-3` vs `main` (baseline `1158426`). **Auditor: 0 High AC violations**; the `PositionAcbService` extraction verified not to change 3.1/3.2 behavior. Hunters found real correctness gaps (mostly around the rare same-ticker-twice scenario). Triage: 8 patches applied, 5 deferred (3.7/detector), ~6 dismissed.

### Review Findings

- [x] [Review][Patch] Ticker change/merger could rename onto an already-held symbol → duplicate-ticker collision [CorporateActionService] — HIGH — FIXED: `targetHeldElsewhere` guard; such a rename is forced `pending` and `apply` rejects it (merge is 3.7).
- [x] [Review][Patch] `newTicker` not upper-cased server-side → casing-mismatch / unmatched lookups via API/detector [CorporateActionController] — HIGH — FIXED: controller normalizes `newTicker` to uppercase (like `ticker`).
- [x] [Review][Patch] `confirm`/`dismiss` used plain `findById` (no row lock) → double-confirm TOCTOU [CorporateActionService/Repository] — MED — FIXED: `findByIdForUpdate` (`@Lock PESSIMISTIC_WRITE`), matching 3.1/3.2.
- [x] [Review][Patch] Merger with neither ratio nor newTicker confirmed as a no-op marked `applied` [CorporateActionService] — MED — FIXED: rejected with 400.
- [x] [Review][Patch] `applySplit` grew BigDecimal scale unbounded / mismatched the column [PositionLot] — MED — FIXED: `setScale(6, HALF_UP)`.
- [x] [Review][Patch] User-supplied ratio had no range/scale bound → could overflow `numeric(20,8)` (500) [CorporateActionController] — MED — FIXED: reject ≤0 / >1000 / >8dp → 400.
- [x] [Review][Patch] AC8 gap: no multi-lot split test, no multi-match test [tests] — MED — FIXED: added multi-lot split (cost preserved across 2 lots), multi-match-pending, collision-guard, ratio-range, merger-no-op-confirm tests.
- [x] [Review][Patch] Frontend: silent missing-ratio → pending; Confirm shown for un-confirmable stock dividends; reverse split shown as "0.1:1" [CorporateActions.tsx] — MED — FIXED: require ratio/newTicker before record, hide Confirm for stock dividends, show reverse as 1:N.
- [x] [Review][Defer] Multi-match disambiguation on confirm [CorporateActionService] — deferred to 3.7 (position merge)
- [x] [Review][Defer] Duplicate-record idempotency [CorporateActionService] — deferred to the Finnhub detector
- [x] [Review][Defer] Position-deletion handling / orphaned applied actions [resolvePosition] — deferred to 3.7 (no delete path yet)
- [x] [Review][Defer] Dedicated ticker-alias artifact + cross-symbol history linking — deferred to Epics 4/6
- [x] [Review][Defer] `window.location.reload()` → targeted refetch [CorporateActions.tsx] — deferred, UI polish

_Dismissed (verified): null-shares split NPE (not reachable — NOT NULL + parser guarantees non-null shares); single-underscore `replace` (now `replaceAll`, no live bug); first-load error swallowed / reload race (single-user, minor); merger redundant save (harmless); unused `ex_date` (stored by design)._
