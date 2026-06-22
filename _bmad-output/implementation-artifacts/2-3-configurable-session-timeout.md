---
baseline_commit: 6bacfd1
---

# Story 2.3: Configurable session timeout

Status: done

## Story

As the user,
I want to choose the auto-lock timeout,
so that I balance convenience and safety.

## Acceptance Criteria

1. **Pick a timeout** — Settings offers **1m, 5m, 15m (default), 30m, 1h, 4h, Never** (FR-35). The choice persists in Postgres and survives restarts. `GET /api/settings/session-timeout` returns the effective value; `PUT` (session-gated) changes it.
2. **It takes effect** — the chosen timeout is the **idle** lifetime of the Redis session: each authenticated request slides it; after that idle period the session expires and the next call is 401 (re-auth). Changing the timeout affects the current session on its next slide. **Given** a chosen timeout, **When** the idle period elapses, **Then** the session locks and re-auth is required.
3. **"Never"** — no idle expiry: the Redis session has no TTL and the cookie persists across restarts. All other values expire on idle.
4. **One source of truth** — the timeout is read from one place (`SettingsService`), used by both the session store and the cookie. The static `argus.security.session-ttl` becomes only the **default** when nothing is stored. This also reconciles the 2.1/2.2 review finding (fixed cookie Max-Age vs sliding server TTL): the **server TTL is authoritative** — finite timeouts use a session cookie (no Max-Age), so it can't expire mid-activity; "Never" uses a long-lived cookie.
5. **Validation + gating** — `PUT` accepts `null` (Never) or a duration ≥ 60s; anything else → 400. Settings endpoints are session-gated like the rest of `/api/**`.
6. **Verification** — unit/integration tests: PUT then GET round-trip incl. Never; `SessionStore` sets a TTL for finite values and none for Never (Testcontainers Redis); existing auth/session tests stay green; deploy boots (Flyway V4 applies).

## Tasks / Subtasks

- [x] Task 1 — Settings persistence (AC: #1)
  - [x] Flyway `V4__app_settings.sql`: single-row `app_settings` (`id smallint PK CHECK(id=1)`, `session_timeout_seconds bigint NULL`, `updated_at timestamptz`). NULL = Never.
  - [x] `AppSettings` entity + repository (`findSingleton`).
- [x] Task 2 — SettingsService (AC: #1, #4)
  - [x] `sessionTimeout(): Optional<Duration>` (empty = Never; no row → `argus.security.session-ttl` default). In-memory cached (loaded `@PostConstruct`, updated on write) so the per-request session check doesn't hit the DB.
  - [x] `setSessionTimeout(Optional<Duration>)` persists + refreshes the cache.
- [x] Task 3 — Wire into the session layer (AC: #2, #3, #4)
  - [x] `SessionStore` reads `SettingsService.sessionTimeout()` (not the static property): `create()`/`validate()` set/slide a TTL for finite values, no expiry for Never. Add `cookieMaxAge()` (empty = session cookie; long for Never).
  - [x] `SessionCookie.issue(id, Optional<Duration> maxAge, secure)` — session cookie when empty. Update `AuthController` + `WebAuthnController` callers.
- [x] Task 4 — Settings endpoints (AC: #1, #5)
  - [x] `SettingsController` (`/api/settings`): `GET /session-timeout` → `{seconds}` (null = Never); `PUT /session-timeout` body `{seconds}` → validate (null or ≥60) → set. RFC 9457 on bad input.
- [x] Task 5 — Frontend (AC: #1)
  - [x] Profile → Security: a session-timeout selector (the 7 options) that loads the current value and PUTs on change. `apiClient` helpers.
- [x] Task 6 — Tests + verify (AC: #6)
  - [x] Unit/integration: settings round-trip (incl. Never), validation (<60 → 400, gated), `SessionStore` TTL-vs-no-TTL behavior, no regressions, V4 applies.

## Dev Notes

### Foundation [baseline 6bacfd1]
- `SecurityProperties.sessionTtl` (default 15m) currently feeds `SessionStore` (sliding TTL) + the cookie Max-Age. This story makes the value runtime-configurable; the property stays as the fallback default. [Source: security/SecurityProperties.java, SessionStore.java]
- `SessionStore` (Redis, `argus:session:{id}`) + `SessionCookie` (HttpOnly/Secure[config]/SameSite=Strict) + `SessionAuthFilter` gate are from 2.1; biometric login (2.2) mints via the same `SessionStore`. Both `AuthController.login` and `WebAuthnController.loginFinish` issue the cookie. [Source: security/*]
- Patterns: `@ConfigurationProperties` (`SecurityProperties`), single-row table + `CHECK(id=1)` (`app_credential`, V2), `findSingleton` repo default, RFC 9457 via `GlobalExceptionHandler` (incl. `BadRequestException`). Testcontainers PG18+Redis8. [Source: security/AppCredential*, V2, common/*]
- **Review reconciliation:** 2.1/2.2 reviews flagged the fixed cookie Max-Age vs sliding server TTL. This story resolves it by making the server TTL authoritative + a session cookie for finite timeouts.

### Architecture
- Server-side Redis sessions, instant revocation (Decision 5). Timeout is per-FR-35 (1m…4h…Never, default 15m). [Source: architecture.md#Decision 5; prd.md#FR-35]

### Project Structure Notes
- New: `security/AppSettings.java`, `AppSettingsRepository.java`, `SettingsService.java`, `SettingsController.java` (+ DTO); `db/migration/V4__app_settings.sql`. Modified: `SessionStore`, `SessionCookie`, `AuthController`, `WebAuthnController`.

### References
- [Source: epics.md#Story 2.3] [Source: prd.md#FR-35] [Source: 2-1-…/2-2-… review findings on cookie/TTL]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **54 tests pass** (+7), BUILD SUCCESS. Frontend lint+build clean.

### Completion Notes List

- **Persistence (Task 1):** Flyway `V4__app_settings.sql` (single-row, `session_timeout_seconds bigint NULL` = Never). `AppSettings` entity + repo.
- **SettingsService (Task 2):** `sessionTimeout(): Optional<Duration>` (empty = Never; no row → `argus.security.session-ttl` default 15m). **In-memory cached** (loaded `@PostConstruct`, refreshed on write) so the per-request session check never hits the DB.
- **Session layer (Task 3):** `SessionStore` now reads `SettingsService` (not the static property): finite → set/slide TTL; Never → no expiry (`hasKey` validate). New `cookieMaxAge()` — **empty = session cookie** (finite; server TTL authoritative), long for Never. `SessionCookie.issue(id, Optional<Duration>, secure)` updated; `AuthController` + `WebAuthnController` callers updated. **This reconciles the 2.1/2.2 review finding** (fixed cookie Max-Age vs sliding server TTL).
- **Endpoints (Task 4):** `SettingsController` `GET/PUT /api/settings/session-timeout` (session-gated); PUT validates null (Never) or 60–86400s → RFC 9457 400 otherwise.
- **Frontend (Task 5):** `SessionTimeoutSetting` selector (7 FR-35 options) in Profile → Security; loads current, PUTs on change, reverts on failure. `apiClient` get/set helpers + `apiPut`.
- **Tests (Task 6):** `SettingsIntegrationTest` — gating, default 15m, finite + Never round-trip, <60 → 400, and `SessionStore` TTL-set vs no-expiry behavior (Testcontainers Redis).

### File List

**New (backend):** `db/migration/V4__app_settings.sql`, `security/AppSettings.java`, `AppSettingsRepository.java`, `SettingsService.java`, `SettingsController.java`, `test/.../security/SettingsIntegrationTest.java`
**New (frontend):** `features/auth/SessionTimeoutSetting.tsx`
**Modified:** `security/SessionStore.java`, `SessionCookie.java`, `AuthController.java`, `security/webauthn/WebAuthnController.java`, `lib/apiClient.ts`, `app/(dashboard)/profile/page.tsx`

## Code Review (2026-06-22)

Adversarial review — Blind + Edge + Auditor (Opus 4.8), diff `6bacfd1..HEAD`. **All 6 ACs PASS** (Auditor). 2 findings patched:

- [x] [Review][High] **finite→Never left a stale Redis TTL** — switching to "Never" didn't touch live sessions, so a session created under a finite timeout kept its countdown and expired anyway (violated AC#3 for in-flight sessions). Fix: `SettingsService.setSessionTimeout` now **reconciles active sessions** — `persist` (strip TTL) for Never, re-`expire` for finite — so the change applies immediately. Tests: `switchingToNeverPersistsExistingSession`, `switchingToFiniteAppliesTtlToExistingSession`.
- [x] [Review][Med] **Empty PUT body → NPE/500** — `@RequestBody(required=false)` + null guard → clean 400. Test: `emptyBodyIsBadRequest`.

**Deferred/dismissed:** Never→finite long-lived cookie (cosmetic — server TTL governs auth and reconcile applies the TTL immediately); session-cookie ⇒ re-auth after a full PWA/browser restart (the intended "server-authoritative" trade-off — noted to the user); missing `seconds` field ≡ Never (acceptable per AC; the frontend always sends it); DB-down-at-startup fail-fast (intended); no seed row (by design).

_Re-verified: 57 backend tests pass; frontend lint+build clean._

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story drafted — configurable session timeout; reconciles the cookie/TTL review finding. |
| 2026-06-22 | Implemented: V4 app_settings, cached SettingsService, runtime timeout in SessionStore (finite TTL / Never no-expiry), server-authoritative cookie, GET/PUT endpoints, Profile selector. 54 tests. Status → review. |
| 2026-06-22 | Code review (3 layers): all 6 ACs pass. Patched 2 (live-session reconciliation on timeout change; empty-body 400). 57 tests pass. Status → done. |
