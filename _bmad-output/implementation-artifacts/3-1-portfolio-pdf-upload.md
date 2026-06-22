---
baseline_commit: a7740b5
---

# Story 3.1: Portfolio PDF upload

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to upload a brokerage statement PDF,
so that my holdings load without manual entry.

## Acceptance Criteria

1. **Upload + parse** — `POST /api/portfolio/imports` (session-gated, `multipart/form-data`, field `file`) accepts a brokerage statement PDF. **Given** a statement PDF, **When** I upload it, **Then** the response returns the extracted holdings — each with `ticker`, `shares`, `costBasis` (+ currency), and `acquisitionDate` — within 60 seconds. (FR-1)
2. **No silent drops** — Fields (or whole rows) that cannot be parsed are returned with the field `null` and the row marked `needsReview: true` plus a per-field reason, so they are surfaced for manual entry rather than dropped. An empty/garbage/zero-holding parse returns a parse result with `holdings: []` and a clear top-level message — it does **not** 500 and does **not** silently succeed. (FR-1 consequence #2)
3. **Confirm before overwrite** — The upload produces a **staged preview** (persisted as an import batch with status `pending`); existing confirmed positions are **not** mutated until I confirm via `POST /api/portfolio/imports/{id}/confirm`. **Given** previously confirmed positions, **When** a new import would change them, **Then** confirmation is required first — upload alone never overwrites confirmed data. (FR-1 consequence #3)
4. **Holdings persist** — On confirm, parsed holdings are written to the `positions` table (cost basis as `NUMERIC` with explicit currency; dates as `date`); `GET /api/portfolio/positions` returns them. Money is `BigDecimal`/`NUMERIC` end-to-end, never float. (architecture money rule)
5. **Validation + errors** — Non-PDF content type, missing file, or a file over the configured size limit return **RFC 9457 Problem Details** (not an ad-hoc shape), via the existing `@RestControllerAdvice`. Multipart limits are raised from the Spring 1MB default to accommodate real statements.
6. **Frontend upload flow** — The Portfolio screen gains an "Import statement" action: pick a PDF → see a parsed-preview table with flagged (`needsReview`) rows visually distinct → Confirm import. On success the holdings table reflects the imported positions. Loading + error states use the established TanStack Query + skeleton + `ApiError` patterns.
7. **Tests** — A Testcontainers integration test uploads a fixture PDF end-to-end (parse → preview → confirm → `GET positions`), asserts a flagged unparseable field is preserved (not dropped), and asserts non-PDF / oversize uploads return Problem Details. `./mvnw verify` green; frontend lint + build clean.

## Tasks / Subtasks

- [x] **Task 1 — PDF text extraction library** (AC: #1)
  - [x] Added Apache **PDFBox 3.0.7** (latest on Maven Central, verified) to `backend/pom.xml`. 3.x API used: `Loader.loadPDF(byte[])` + `PDFTextStripper`.
  - [x] Raised multipart limits in `application.yml` (`max-file-size: 15MB`, `max-request-size: 20MB`).
- [x] **Task 2 — Domain model + migration** (AC: #3, #4)
  - [x] `V5__portfolio_positions.sql` (forward-only, snake_case): `positions` + `portfolio_imports` (staged preview in a `raw_holdings jsonb` column). NUMERIC money, `timestamptz`, `idx_positions_ticker`.
  - [x] JPA entities `Position` + `PortfolioImport` + repos, following the `AppSettings` idiom (`@Column(name=…)` mapping, `protected` no-arg ctor, `BigDecimal`/`LocalDate`/`Instant`). `raw_holdings` mapped via `@JdbcTypeCode(SqlTypes.JSON)` on a `String` (service owns (de)serialization).
  - [x] Everything in `com.argus.portfolio`; tests mirror under `src/test/java/com/argus/portfolio`.
- [x] **Task 3 — Parsing service** (AC: #1, #2)
  - [x] `StatementParser` (deterministic, no LLM): PDFBox text → line heuristics → holdings; unparsed fields left `null` + named in `issues`, row flagged `needsReview` (never dropped). Date stripped before number scan so date digits aren't misread as money.
  - [x] No-table / empty / non-statement PDFs → zero holdings + a user message; a non-PDF byte stream → `BadRequestException` (400), never a 500.
- [x] **Task 4 — Endpoints** (AC: #1, #3, #4, #5)
  - [x] `PortfolioImportController` (`/api/portfolio`, session-gated by `SessionAuthFilter`, not allowlisted): `POST /imports` (multipart, 201) → stage pending batch + preview; `POST /imports/{id}/confirm` (200) → commit holdings; `GET /positions` (200). Validation via `BadRequestException` (missing/non-PDF) + a controller byte-ceiling guard → new `PayloadTooLargeException` (413).
  - [x] Success returns the resource directly; camelCase JSON; money + currency carried. Added a `PayloadTooLargeException` handler to `GlobalExceptionHandler` (servlet `MaxUploadSizeExceededException` is already mapped to 413 by the `ResponseEntityExceptionHandler` base — no duplicate handler).
- [x] **Task 5 — Frontend** (AC: #6)
  - [x] `src/features/portfolio/ImportStatement.tsx`: file picker → `uploadStatement` → preview table (flagged rows marked with the `warning` token + issues tooltip) → Confirm → commits + lists current holdings. `lib/apiClient.ts` gains `uploadStatement` (multipart `FormData`, `credentials: include`, no manual Content-Type) / `confirmImport` / `listPositions` + `ParsedHolding`/`ImportPreview`/`Position` types.
  - [x] Integrated into `app/(dashboard)/portfolio/page.tsx` above the dummy prototype widgets (left intact for 3.4–3.6).
- [x] **Task 6 — Tests + verify** (AC: #7)
  - [x] `StatementParserTest` (6, no Spring) — full row, flagged partial row, date-not-money regression, summary-line skip, empty→message, non-PDF→400. `PortfolioImportIntegrationTest` (5, Testcontainers) — upload→preview→confirm→list with the flagged field preserved (`costBasis` null + `needsReview`), confirm-twice→409, non-PDF→problem+json, empty→message, gating. `PortfolioImportSizeLimitTest` (1) — oversize→413 problem+json. Fixture PDFs built in-memory via `PdfFixtures` (no binaries in the repo).
  - [x] `./mvnw verify` → **78 tests, 0 failures** (+12); `npm run lint` + `npm run build` clean.

## Dev Notes

### Why deterministic parsing (no LLM) — design decision for this story
Parsing is done with **PDFBox text extraction + heuristic rules, not the Model Gateway / LLM**, for three reasons: (1) it keeps Story 3.1 fully buildable and testable on the **MacBook** with no dependency on the Mac Mini 26B model — consistent with the Epic 3 "laptop-only, no deploy" plan; (2) extraction must be deterministic and unit-testable against a fixture; (3) the platform's framing rule keeps the LLM out of number generation. LLM-assisted parsing of messy/scanned statements is a reasonable **future enhancement** (would route through `com.argus.model`, validated on the Mini) but is explicitly out of scope here. *(Flagged as an open question below for confirmation.)*

### Scope boundary — don't over-build the portfolio model
This is the **first** story to touch `com.argus.portfolio`, so it establishes the `positions` table — but keep it minimal and forward-compatible. Do **not** build here:
- **ACB lots + purchase-time FX** → Story 3.2 (will add a `position_lots` table + CAD ACB columns + `fx_estimated` flag). For 3.1, store cost basis in its original currency only.
- **Corporate-actions** (splits/ticker changes) → Story 3.3.
- **Full manual add/edit/remove + audit log** → Story 3.7. (3.1 only *flags* unparseable fields; editing them is 3.7.)
- **Live price / value / P&L** → Story 3.4 (Finnhub WS). 3.1 stores static holdings; current value comes later.
Design `positions` columns so 3.2/3.3 extend rather than rewrite.

### Architecture / convention guardrails (mandatory)
- **Money:** `NUMERIC` in PG, `BigDecimal` in Java — never float. Always carry currency. [Source: architecture.md#Format Patterns]
- **Case boundary:** snake_case columns ↔ camelCase Java/JSON. JPA maps it; Spring emits camelCase JSON. No camelCase columns, no snake_case JSON. [Source: architecture.md#Naming Patterns]
- **REST:** plural kebab-case paths under `/api`, no version prefix, resource returned directly (no envelope), errors as RFC 9457 Problem Details via the existing `@RestControllerAdvice`. [Source: architecture.md#Decision 6, #Format Patterns]
- **Structure:** feature package `com.argus.portfolio` owns controller/service/repository/domain; frontend `src/features/portfolio`. Never layer-first dirs. [Source: architecture.md#Structure Patterns]
- **Session gating:** new `/api/portfolio/**` endpoints are gated by the existing `SessionAuthFilter` (Story 2.1/2.7) — do not add them to the allowlist. [Source: 2-7-remote-session-kill.md]
- **Dates:** `date` for acquisition date (no tz); `timestamptz` + `Instant` for audit timestamps; render America/Toronto in UI. [Source: architecture.md#Format Patterns]
- **Flyway:** forward-only `V5__*.sql`; `ddl-auto: validate` (Flyway owns schema). Next free version is **V5** (V1–V4 exist). [Source: project memory — DB conventions; backend/src/main/resources/db/migration]

### Files to touch
- **New (backend):** `portfolio/Position.java`, `portfolio/PortfolioImport.java`, `portfolio/PositionRepository.java`, `portfolio/PortfolioImportRepository.java`, `portfolio/StatementParser.java`, `portfolio/PortfolioImportService.java`, `portfolio/PortfolioImportController.java`, `resources/db/migration/V5__portfolio_positions.sql`, test fixture PDF + `test/.../portfolio/PortfolioImportIntegrationTest.java`.
- **Modified (backend):** `pom.xml` (PDFBox), `resources/application.yml` (multipart limits), possibly `common/GlobalExceptionHandler` (max-upload-size → Problem Details).
- **New (frontend):** `src/features/portfolio/ImportStatement.tsx` (+ any small sub-components/hooks).
- **Modified (frontend):** `lib/apiClient.ts` (client fns + types), `app/(dashboard)/portfolio/page.tsx` (import entry point).

### Existing-code context (read before editing)
- `app/(dashboard)/portfolio/page.tsx` is a **dummy-data prototype** (Story 1.7) — `PriceChart`, `HoldingsTreemap`, `PerformanceGauges`, `MotionCard`. Preserve it; add the import action alongside. Real data wiring is 3.4–3.6.
- `lib/apiClient.ts` — typed client: success returns resource directly; `ApiError` wraps problem+json; `credentials: "include"`; `BASE_URL` from env. Follow this exactly. For multipart, pass a `FormData` body and let the browser set the boundary `Content-Type`.
- `security/SettingsController.java` + `AppSettings.java` + `AppSettingsRepository.java` — the canonical controller/entity/repository idiom to mirror (records for DTOs, `BadRequestException` for validation, JpaRepository).
- `test/.../security/SessionManagementIntegrationTest.java` — the Testcontainers integration-test pattern to mirror.

### Testing standards
- Backend: Spring Boot test + **Testcontainers** (Postgres + Redis) for integration; `TestcontainersConfiguration` is shared/public (Boot 4). Run `./mvnw verify`.
- Frontend: `npm run lint` + `npm run build` must be clean (the project's standing gate; no component test harness in use yet).

### Latest tech notes
- **Apache PDFBox 3.0.x** is the current major line. API change vs 2.x: use `Loader.loadPDF(File|byte[])` (static `PDDocument.load` removed); text via `new PDFTextStripper().getText(doc)`. Confirm exact latest patch on Maven Central at implementation time (per the project's "verify real version" gotcha — `start.spring.io`/cached metadata have lied before).
- Spring Boot 4 multipart: `MaxUploadSizeExceededException` is thrown when limits are exceeded; ensure the global advice maps it to Problem Details (otherwise it surfaces as a raw 500/multipart error).

### Project Structure Notes
- Aligns with `architecture.md#Requirements → Structure Mapping` (F1/F2 Portfolio → backend `portfolio`/`marketdata`, frontend `features/portfolio`). `marketdata` stays untouched this story (it's for 3.4 live prices).
- No variances from the unified structure. `positions`/`portfolio_imports` are new tables; no existing schema is altered.

### References
- [Source: epics.md#Epic 3 / Story 3.1] — story + BDD acceptance criteria.
- [Source: prd.md#FR-1 Portfolio Upload via PDF] — 60s display, no silent drop, no overwrite without confirmation; #A-14 ≥95% parse-accuracy target (assumption); §6.2 brokerage API sync is Phase 3 / out of scope.
- [Source: architecture.md#Decision 3] — Postgres holds portfolio/positions/ACB lots; #Naming/Format/Structure Patterns — money/case/REST rules; #Requirements→Structure Mapping.
- [Source: 2-7-remote-session-kill.md] — session-gating, Problem Details advice, Testcontainers integration-test style.
- [Source: project memory] — Flyway/Boot-4 gotchas, `ddl-auto: validate`, forward-only migrations, dev profile = laptop/no 26B model.

## Open Questions (for the user / dev — non-blocking)
1. **Parsing approach:** Story scopes deterministic PDFBox parsing (no LLM) so it's laptop-buildable and testable. Confirm you're happy deferring LLM-assisted parsing of messy/scanned statements to a later enhancement (it would need the Mini's model to validate).
2. **ACB convention (from FR-1b/A-16):** weighted-average ACB across lots (Canadian non-registered convention) is the assumption — relevant to 3.2, but it affects how 3.1 should store cost basis. 3.1 stores original-currency cost basis only and leaves CAD ACB to 3.2; confirm that split is fine.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 78 tests, 0 failures/0 errors** (+12 vs the 66 baseline).
- `npm run lint` clean; `npm run build` (Next 16 / Turbopack) compiles + typechecks clean.
- Two startup failures hit and fixed during the run: (1) **ambiguous `@ExceptionHandler`** — the `ResponseEntityExceptionHandler` base already maps `MaxUploadSizeExceededException`, so my duplicate handler conflicted; removed it (kept only `PayloadTooLargeException`). (2) **no injectable `ObjectMapper` bean** in this Boot 4 context — switched the staged-preview JSON round-trip to a service-owned **Jackson 3** (`tools.jackson`) mapper, which handles `LocalDate`/`BigDecimal` natively (the `com.fasterxml` Jackson 2 jsr310 module is runtime-scoped / not compile-visible).

### Completion Notes List

- Deterministic, **LLM-free** PDF parsing (PDFBox 3.0.7) — keeps Story 3.1 fully laptop-buildable (no Mac Mini / 26B-model dependency) and unit-testable, per the Epic 3 plan and the framing rule.
- **Confirm-before-overwrite** honored: upload only stages a `pending` batch; `positions` stay empty until `POST /imports/{id}/confirm` (asserted in the integration test).
- **No silent drops**: partially-parsed rows are kept with `needsReview` + per-field `issues`; an empty/garbage PDF returns `holdings: []` + a message (201), never a 500.
- **Jackson note for future stories:** this Boot 4 app runs on **Jackson 3 (`tools.jackson`)** for MVC; there is no injectable `ObjectMapper` bean — own a `JsonMapper.builder().build()` when you need one (it supports `java.time`/`BigDecimal` with no module). Jackson 2 is also on the classpath (yubico) but its jsr310 module is runtime-only.
- **Scope kept tight** (deferred as planned): ACB lots + purchase-time FX → 3.2; corporate actions → 3.3; live value/P&L → 3.4; full sortable holdings table + manual CRUD/audit → 3.5/3.7. `companyName` is modelled but not yet extracted by the parser (nullable; populated later). The prototype `PriceChart`/`HoldingsTreemap`/`PerformanceGauges` still render dummy data.
- Open questions from story creation stand as resolved-by-default: parsing is deterministic/no-LLM; cost basis stored in original currency only (CAD ACB is 3.2).

### File List

**New (backend):** `portfolio/Position.java`, `portfolio/PortfolioImport.java`, `portfolio/PositionRepository.java`, `portfolio/PortfolioImportRepository.java`, `portfolio/ParsedHolding.java`, `portfolio/StatementParser.java`, `portfolio/PortfolioImportService.java`, `portfolio/ImportPreview.java`, `portfolio/PositionView.java`, `portfolio/PortfolioImportController.java`, `common/PayloadTooLargeException.java`, `resources/db/migration/V5__portfolio_positions.sql`
**New (backend tests):** `portfolio/PdfFixtures.java`, `portfolio/StatementParserTest.java`, `portfolio/PortfolioImportIntegrationTest.java`, `portfolio/PortfolioImportSizeLimitTest.java`
**Modified (backend):** `pom.xml` (PDFBox 3.0.7), `resources/application.yml` (multipart limits), `common/GlobalExceptionHandler.java` (413 handler)
**New (frontend):** `src/features/portfolio/ImportStatement.tsx`
**Modified (frontend):** `src/lib/apiClient.ts` (import/positions client + types), `src/app/(dashboard)/portfolio/page.tsx` (import entry point)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story created (create-story workflow). First story of Epic 3; establishes `com.argus.portfolio` + `positions`/`portfolio_imports` schema. Status → ready-for-dev. |
| 2026-06-22 | Implemented PDF statement import (FR-1): PDFBox 3.0.7 deterministic parser, staged-import → confirm flow (confirm-before-overwrite), flagged-not-dropped fields, RFC 9457 errors incl. 413, `ImportStatement` UI. 78 backend tests (+12) green; frontend lint+build clean. Status → review. |
| 2026-06-22 | Code review (3 adversarial layers): all 7 ACs pass. Applied 5 patches — NUMBER-regex ≥1000 split (High), non-ISO date number pollution, confirm TOCTOU (pessimistic lock), non-positive-shares plausibility flag, frontend post-confirm refresh. 7 items deferred (3.2/3.7/parser-refinement), 6 dismissed. 82 backend tests (+4) green; frontend lint+build clean. Status → done. |

## Code Review (2026-06-22)

Adversarial 3-layer review (Blind Hunter + Edge Case Hunter + Acceptance Auditor, Opus 4.8), diff vs baseline `a7740b5`. **Acceptance Auditor: all 7 ACs pass** (0 High/Med AC violations). The hunters surfaced real correctness issues in the parser heuristics + confirm path; triaged into 5 patches and 7 deferrals (6 dismissed as noise / handled elsewhere).

### Review Findings

- [x] [Review][Patch] NUMBER regex splits any ≥1000 value written without thousands separators (`12345.67`→`123`+`45.67`); wrong shares/cost committed **unflagged** [StatementParser.java NUMBER pattern] — **HIGH** — FIXED: `(?:,\d{3})*`→`(?:,\d{3})+` so un-separated ≥1000 values match whole; regression tests added.
- [x] [Review][Patch] Unrecognized (non-ISO) date digits pollute the number scan → wrong "clean" shares/cost [StatementParser.java parse()] — MED — FIXED: strip any `DATE_LIKE` token (`-`/`/` separators) before the number scan; ISO still parsed for the value, other formats flagged.
- [x] [Review][Patch] `confirmImport` TOCTOU: read-status→write→mark with no row lock [PortfolioImportService.java confirmImport] — MED — FIXED: `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) serializes concurrent confirms; the loser sees `confirmed`→409.
- [x] [Review][Patch] Non-positive shares / negative cost basis accepted as a clean holding [StatementParser.java toHolding] — MED — FIXED: `shares ≤ 0` / `costBasis < 0` now add an issue + set `needsReview`.
- [x] [Review][Patch] Frontend discards confirm's returned `Position[]` and shows a misleading error if the follow-up refresh fails [ImportStatement.tsx handleConfirm] — LOW — FIXED: uses the confirm response; a failed best-effort re-list keeps the returned rows and shows no error.
- [x] [Review][Defer] Duplicate-position accumulation on re-import / multi-lot [PortfolioImportService.java confirmImport] — deferred to 3.2 (ACB lots/merge) / 3.7
- [x] [Review][Defer] Silent USD currency default when no currency token [StatementParser.java toHolding] — deferred to 3.2 (FX/ACB)
- [x] [Review][Defer] Heuristic limits: junk-line-with-numbers, positional column assumption, multi-line rows, non-ISO date *parsing* [StatementParser.java] — deferred, future parser refinement
- [x] [Review][Defer] Unbounded extracted-text / page-count on a malicious PDF [StatementParser.java extractText] — deferred, low risk (single-user/Tailscale)
- [x] [Review][Defer] BigDecimal→JS number precision for extreme values [apiClient.ts types] — deferred, exact at realistic magnitudes
- [x] [Review][Defer] `raw_holdings` jsonb read has no failure path for future schema drift [PortfolioImportService.java read] — deferred, future-proofing
- [x] [Review][Defer] Multi-line / wrapped holding rows [StatementParser.java parse] — deferred, future parser refinement

_Dismissed as noise/handled: content-type leniency (PDFBox parse failure → 400 is the real validator), `getSize()` exact-limit/`-1` boundary (servlet `max-*-size` is authoritative), empty-holdings confirm no-op (harmless; frontend guards), unmount-mid-upload setState (low React nit), `companyName` not extracted (intended/in-scope), upload problem+json handling (`toApiError` covers it)._
