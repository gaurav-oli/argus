---
baseline_commit: 396c950
---

# Story 3.7: Manual position edit

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to add, edit, or remove individual positions by hand,
so that I can correct or update my portfolio without re-uploading a whole statement.

## Acceptance Criteria

1. **Add (FR-5)** — `POST /api/portfolio/positions` with `{ ticker, companyName?, shares, costBasis, currency, acquisitionDate? }` creates a position with one lot (`source = manual`), resolves its purchase-time FX (reusing the Story-3.2 path: CAD → 1; USD + date → BoC lookup; else flagged `fxEstimated`), recomputes the ACB caches, and returns the new `PositionView`.
2. **Edit (FR-5)** — `PUT /api/portfolio/positions/{id}` updates the position: `companyName` and `ticker` may always change; `shares`/`costBasis`/`currency`/`acquisitionDate` edit the holding's lot for a **single-lot** position (then re-resolve FX + recompute). A **multi-lot** position (from corporate actions) rejects share/cost/currency/date edits with a clear 400 (lot-level editing is a later enhancement); metadata edits still apply.
3. **Remove (FR-5)** — `DELETE /api/portfolio/positions/{id}` deletes the position and its lots (FK cascade). Returns 204.
4. **Immediate recalculation (FR-5)** — after any add/edit/remove, the recomputed values are reflected **immediately**: the live snapshot is re-pushed to `/topic/portfolio` so the value summary + holdings table update without a manual refresh.
5. **Audit trail (FR-5)** — every manual change is recorded with a **timestamp** in a persisted audit log (action `created|updated|removed`, ticker, a human-readable detail); `GET /api/portfolio/audit` returns recent entries (newest first).
6. **Validation + errors** — required fields validated via `BadRequestException` (RFC 9457); positive shares; positive cost; `NotFoundException` for an unknown id; session-gated; money `BigDecimal`/`NUMERIC`; `ticker`/`currency` uppercased.
7. **Tests** — backend integration: add → `GET positions` shows it (with CAD ACB via stubbed FX); edit single-lot updates shares/cost + recompute; edit a multi-lot position's shares → 400; remove → gone (and lots gone); audit log records all three with timestamps; endpoints session-gated. `./mvnw verify` green; frontend `lint`+`build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Audit persistence** (AC: #5): `V9__position_audit.sql` → `position_audit(id, ticker, action, detail, created_at)`. `PositionAudit` entity + repo (`findTop50ByOrderByCreatedAtDesc`).
- [x] **Task 2 — Manual CRUD service** (AC: #1–#5): `ManualPositionService` (add/edit/remove) — builds/edits the lot, resolves FX (shared with the import path; CAD→1 / USD+date→`FxRateService` / else estimated), runs `PositionAcbService.recompute`, writes a `PositionAudit` row, and calls a new `LivePortfolioService.pushCurrent()` so the snapshot re-broadcasts. Multi-lot guard on data edits. Add entity mutators: `PositionLot.edit(...)`, `Position.setCompanyName(...)`.
- [x] **Task 3 — Endpoints** (AC: #1–#3, #5, #6): `POST /api/portfolio/positions`, `PUT /api/portfolio/positions/{id}`, `DELETE /api/portfolio/positions/{id}` (204), `GET /api/portfolio/audit` — on a new `ManualPositionController` under `/api/portfolio` (session-gated, RFC 9457, camelCase, records as request DTOs).
- [x] **Task 4 — Frontend** (AC: #1–#5): `features/portfolio/ManagePositions.tsx` — an "Add position" form + per-position Edit (inline) / Remove, driven by `listPositions`; on change it refreshes. `lib/apiClient.ts` gains `addPosition`/`editPosition`/`removePosition`/`listAudit` + an `AuditEntry` type. Placed on the portfolio page; mirrors `ImportStatement.tsx` patterns.
- [x] **Task 5 — Tests + verify** (AC: #7): integration as above; `./mvnw verify`; `npm run lint`/`build`.

## Dev Notes

### Builds on 3.1/3.2/3.4 (read these)
- Lots are the cost source of truth (3.2); a manual position is **one lot**. Edit/add go through the lot, then `PositionAcbService.recompute` (3.3) updates the `positions` caches — **never set `shares`/`costBasis`/`cadAcb` directly**.
- FX resolution mirrors `PortfolioImportService.newLot` (CAD→1, USD+date→`FxRateService.usdCadOn`, else `fxEstimated`). Replicate the few lines (or extract a small helper); keep it identical so manual + imported lots behave the same.
- **Immediate update** uses a new `LivePortfolioService.pushCurrent()` (publishes `currentSnapshot()` to `/topic/portfolio`) — the value summary (3.4) + holdings table (3.5) already subscribe, so they refresh with no client round-trip.
- `Position` gains `setCompanyName`; `PositionLot` gains `edit(shares, totalCost, tradeCurrency, tradeDate, fxToCad, fxEstimated)`. `findByTicker` already exists; uppercase tickers on the boundary (consistent with 3.3 controller).

### Architecture / convention guardrails
- **Money/shares:** `BigDecimal`/`NUMERIC`, never float; validate positive. **Case:** snake_case ↔ camelCase; uppercase ticker/currency. [architecture.md#Naming/Format Patterns]
- **REST:** `/api/portfolio/...` plural, resource returned directly (DELETE → 204), RFC 9457 via `GlobalExceptionHandler`; session-gated by `SessionAuthFilter` (not allowlisted).
- **Flyway:** next free version **V9** (V1–V8 exist); forward-only; `ddl-auto: validate`.
- **Live push:** reuse `LivePushService`/`/topic/portfolio` — manual changes re-broadcast the snapshot (don't invent a new channel). [architecture.md#Decision 4]
- **Tests:** Testcontainers + `@MockitoBean FxRateClient`; JUnit Jupiter; no live network.

### Scope boundaries
- **Multi-lot editing / per-lot CRUD** (split a position into lots, edit individual lots) → not in MVP; data edits to a multi-lot position are rejected with a clear message (metadata still editable).
- **Undo / full edit history replay** → out; the audit log is append-only for visibility, not transactional rollback.
- **Bulk edit / CSV** → out.

### Files to touch
- **New (backend):** `portfolio/PositionAudit.java`, `portfolio/PositionAuditRepository.java`, `portfolio/ManualPositionService.java`, `portfolio/ManualPositionController.java`, `resources/db/migration/V9__position_audit.sql`, test `portfolio/ManualPositionIntegrationTest.java`.
- **Modified (backend):** `portfolio/Position.java` (`setCompanyName`), `portfolio/PositionLot.java` (`edit`), `portfolio/LivePortfolioService.java` (`pushCurrent`).
- **New (frontend):** `src/features/portfolio/ManagePositions.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts`, `src/app/(dashboard)/portfolio/page.tsx`.

### References
- [Source: epics.md#Epic 3 / Story 3.7] — add/edit/remove; immediate recalculation; timestamped audit.
- [Source: prd.md#FR-5] — manual add/edit/remove without re-upload; immediate; audit-logged. [FR-1b] cost/FX reuse.
- [Source: architecture.md#Decision 4 (live push), #Format Patterns]; [Source: 3-2 (lots/FX), 3-3 (recompute/PositionAcbService), 3-4 (snapshot/push)].

## Dev Agent Record

### Agent Model Used
claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Completion Notes List
- Manual add/edit/remove via lots + `PositionAcbService` recompute, FX resolved like the import path; multi-lot data-edit guarded.
- Every change audited with a timestamp (`position_audit`, `GET /api/portfolio/audit`); snapshot re-pushed for immediate UI update.
- `ManagePositions` UI (add form + per-row edit/remove).

### File List
(see commit)

## Change Log
| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented manual position edit (FR-5): add/edit/remove + audit trail + immediate re-push. Status → review (batch). |


## Code Review (2026-06-23, Epic-3 batch)

Reviewed in the combined 3.4–3.9 adversarial batch review (Blind + Edge + Acceptance Auditor, Opus 4.8). **0 High AC violations**; verdict pass. Fixes applied in-batch and deferrals logged in `deferred-work.md`. Status → done.
