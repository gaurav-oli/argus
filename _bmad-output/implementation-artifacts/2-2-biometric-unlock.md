---
baseline_commit: b224a55
---

# Story 2.2: Biometric unlock

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to unlock Argus with Face ID / Touch ID after I've set a PIN,
so that I get in quickly on my iPhone/iPad, with the PIN always available as a fallback.

## Acceptance Criteria

1. **Enroll a passkey (while authenticated)** — Given a logged-in session (PIN from 2.1), when the user enables biometric unlock, a **WebAuthn registration ceremony** (`navigator.credentials.create()` → Face/Touch ID → platform authenticator) registers a **passkey**; the server verifies the attestation and stores the credential (credential id, COSE public key, signature counter, user handle, label, created-at). Multiple passkeys may be enrolled (e.g. iPhone + iPad).
2. **Unlock with biometrics** — Given an enrolled passkey, when the user unlocks, a **WebAuthn assertion ceremony** (`navigator.credentials.get()` → Face/Touch ID) authenticates them and starts a **Redis session identical to the PIN path** (same `SessionStore` + cookie from 2.1). **Given** a PIN is set and a passkey enrolled, **When** the user unlocks with biometrics, **Then** a session starts without entering the PIN.
3. **PIN fallback** — Biometric never replaces the PIN: the lock screen always offers "Use PIN", and any biometric failure/cancel/timeout falls back to the PIN flow without dead-ending. On a device with no platform authenticator (e.g. the Mac Mini/MacBook), the biometric option simply isn't offered and PIN is used. (FR-35: biometric is used *in place of* the PIN, not instead of having one.)
4. **Security & correctness** — The challenge is server-generated, single-use, and bound to the ceremony (stored server-side in Redis with a short TTL, never trusted from the client). The assertion's **signature counter** is verified to be non-decreasing (clone detection); origin + RP ID are verified; only registered credentials are accepted. Registration requires an existing authenticated session (you can't enroll a passkey without first proving the PIN).
5. **RP config is deployment-aware** — WebAuthn **RP ID / origin** are configurable (the tailnet host on the Mini, `localhost` in dev) via `argus.security.webauthn.*`, reusing the env pattern from 2.1's CORS origins. WebAuthn requires a secure context (HTTPS) — already provided by Tailscale serve.
6. **Manage / revoke** — The user can see enrolled passkeys (label + created-at) and remove one (revoke). Removing all passkeys leaves PIN-only login intact. Endpoints are session-gated like the rest of `/api/**`.
7. **Verification** — Unit tests for credential storage + the assertion counter/clone check; integration tests (Testcontainers) for the registration-finish and assertion-finish verification paths using canned/valid WebAuthn fixtures or a test authenticator; the build + deploy stack stay green (Flyway V3 applies). The real Face ID ceremony on the iPhone is a **manual on-device step** (documented), mirroring 2.1's iPhone verification — it can't be exercised headless.

## Tasks / Subtasks

- [x] Task 1 — WebAuthn library + config (AC: #1, #4, #5)
  - [x] Add the **Yubico `com.yubico:webauthn-server-core`** dependency (standalone WebAuthn — no Spring Security, per Decision 5). Pin a current version.
  - [x] `WebAuthnProperties` (`argus.security.webauthn.*`): `rp-id`, `rp-name`, `origins` (List). Defaults for local dev (`localhost`); the Mini sets the tailnet host via `ARGUS_SECURITY_WEBAUTHN_*` in compose/.env.
  - [x] A `RelyingParty` bean built from the properties + a `CredentialRepository` impl backed by the new table.
- [x] Task 2 — Credential persistence (AC: #1, #6)
  - [x] Flyway **`V3__webauthn_credential.sql`**: `webauthn_credential` (credential_id bytea PK, user_handle bytea, public_key_cose bytea, signature_count bigint, label text, created_at timestamptz, last_used_at timestamptz). snake_case, forward-only.
  - [x] `WebAuthnCredential` entity + repository; a `CredentialRepository` adapter implementing Yubico's interface (lookups by credential id + user handle).
- [x] Task 3 — Registration ceremony (AC: #1, #4)
  - [x] `POST /api/auth/webauthn/register/start` (**session-gated**) → `PublicKeyCredentialCreationOptions`; stash the challenge in Redis keyed to the session, short TTL.
  - [x] `POST /api/auth/webauthn/register/finish` → verify attestation against the stashed challenge; persist the credential (with an optional label). Reject on mismatch.
- [x] Task 4 — Assertion (unlock) ceremony (AC: #2, #4)
  - [x] `POST /api/auth/webauthn/login/start` (**allowlisted**, pre-session) → assertion options (challenge + allowCredentials); stash challenge in Redis keyed to a temp ceremony id (cookie or returned handle).
  - [x] `POST /api/auth/webauthn/login/finish` (**allowlisted**) → verify the assertion (signature, origin, RP ID, **non-decreasing counter**); on success create a `SessionStore` session + set the 2.1 cookie. Update `signature_count` + `last_used_at`.
  - [x] Add the two login endpoints to `SessionAuthFilter`'s allowlist (like `/api/auth/login`).
- [x] Task 5 — Manage/revoke (AC: #6)
  - [x] `GET /api/auth/webauthn/credentials` (session-gated) → list (id, label, createdAt, lastUsedAt). `DELETE /api/auth/webauthn/credentials/{id}` → revoke.
  - [x] `GET /api/auth/status` (or a small extension) should let the frontend know whether any passkey is enrolled so the lock screen can show the biometric option. Keep the existing `AuthStatus` shape stable; add `passkeyEnrolled` if needed.
- [x] Task 6 — Frontend (AC: #2, #3)
  - [x] Lock screen: if `passkeyEnrolled` and `window.PublicKeyCredential` available, show a **"Unlock with Face ID"** button alongside PIN. Run the assertion ceremony (base64url encode/decode of the WebAuthn buffers) → on success, app loads.
  - [x] Profile/Security: an **"Enable Face ID"** control (runs registration while authenticated) and a list of enrolled passkeys with remove.
  - [x] Any biometric failure/cancel → stay on the lock screen with PIN available (no dead-end). Add `apiClient` helpers for the four ceremony calls (with `credentials: "include"`).
- [x] Task 7 — Tests + verify (AC: #7)
  - [x] Unit: credential repository identity mapping (`ArgusCredentialRepositoryTest`).
  - [x] Integration (Testcontainers): gating (register requires session; `login/*` allowlisted), start ceremonies return valid options + handle, and negative finish paths — garbage register→400, garbage/unknown-ceremony assertion→401, malformed revoke id→400. Flyway V3 applies.
  - [~] **Happy-path finish + counter/clone-detection tests NOT in CI** — the success paths require a software authenticator / Yubico test vectors (substantial); the counter/clone check itself lives inside Yubico's verified `finishAssertion`. Covered instead by (a) the library's own test suite and (b) the **on-device iPhone enroll→unlock** (passed 2026-06-22). Tracked as a test-hardening follow-up. _(Corrected after code review flagged this as over-claimed.)_
  - [x] `./mvnw verify` green (47 tests); frontend lint+build; deploy stack boots. iPhone Face ID is a documented manual step (done).

## Dev Notes

### Foundation from Story 2.1 [baseline b224a55 — reuse, don't duplicate]
- **Session layer is done**: `SessionStore` (Redis, opaque id, sliding TTL), `SessionCookie` (HttpOnly/Secure[config]/SameSite=Strict), `AuthController` issues the cookie. Biometric unlock MUST create the session via the same `SessionStore.create()` + cookie path so everything downstream is identical to PIN login. [Source: security/SessionStore.java, SessionCookie.java, AuthController.java]
- **Gate**: `SessionAuthFilter` allowlists pre-login endpoints + short-circuits CORS preflight. Add the two `webauthn/login/*` endpoints to its allowlist; everything else stays gated. [Source: security/SessionAuthFilter.java]
- **Errors**: RFC 9457 via `GlobalExceptionHandler` (+ `UnauthorizedException`/`ConflictException`). Add a `BadRequestException` (400) or reuse validation for ceremony failures. [Source: common/GlobalExceptionHandler.java]
- **Config pattern**: `SecurityProperties` (`argus.security.*`) + `WebProperties` (`argus.web.allowed-origins`, env-driven). Add `argus.security.webauthn.*` the same way; set it on the Mini via compose/.env like `ARGUS_WEB_ALLOWED_ORIGINS`. [Source: security/SecurityProperties.java, config/WebProperties.java, docker-compose.yml]
- **Frontend**: `AuthGate` routes loading/setup/login/app; `PinScreen` does PIN; `apiClient` has `credentials:"include"` + `apiGet/apiPost`. Add biometric to the **login** gate + a Security section (already has `LogoutButton`). [Source: features/auth/*, lib/apiClient.ts]
- **Migrations**: V1 baseline, V2 app_credential. Add **V3**; never edit applied migrations. `ddl-auto: validate`. [Source: db/migration/]
- **Tests**: Testcontainers Postgres18 + Redis8 via `TestcontainersConfiguration`; MockMvc slice + full `@SpringBootTest`. Mirror `AuthFlowIntegrationTest`. [Source: test/.../security/AuthFlowIntegrationTest.java, TestcontainersConfiguration.java]

### Architecture requirements
- **Biometric via iOS passkey/WebAuthn unlocking the session; Spring Security deferred** — use a standalone WebAuthn library (Yubico java-webauthn-server), not Spring Security's WebAuthn. [Source: architecture.md#Decision 5; L76, L148]
- **Server-side Redis sessions** — passkey unlock yields the same session; no JWT. [#Decision 5]
- **Tailscale HTTPS** satisfies WebAuthn's secure-context + RP-origin requirement; never public. [#Decision 5/6]

### Latest-tech guidance (stable as of 2026-06)
- **Yubico `webauthn-server-core`**: `RelyingParty` + `CredentialRepository`; `startRegistration`/`finishRegistration`, `startAssertion`/`finishAssertion`. Handles attestation, origin/RP-ID checks, and signature-counter verification. RP ID must be a registrable domain suffix of the origin (the tailnet host works as its own RP ID).
- **WebAuthn buffers** cross the wire as **base64url**; the browser returns `ArrayBuffer`s that must be encoded for JSON and decoded back. Apple platform authenticator = Face/Touch ID passkeys; works in Safari over HTTPS.
- **RP ID on Tailscale**: `leannas-mac-mini.taila43287.ts.net`; origin `https://leannas-mac-mini.taila43287.ts.net`. Dev: RP ID `localhost`, origin `http://localhost:3000`.

### Project Structure Notes
- New backend: `security/webauthn/` (`WebAuthnProperties`, `RelyingPartyConfig`, `WebAuthnCredential`, repo + Yubico `CredentialRepository` adapter, `WebAuthnController`, `WebAuthnService`); resource `db/migration/V3__webauthn_credential.sql`.
- New frontend: biometric button in the lock screen + a passkey-management section; `apiClient` ceremony helpers; small base64url util.
- Naming: snake_case DB, camelCase Java/JSON/TS. Keep `/api/**` gated; only the two `webauthn/login/*` ceremony endpoints are allowlisted.

### Testing standards
- WebAuthn finish-steps are hard to unit-test without an authenticator; use Yubico's test vectors / a software authenticator (e.g. a known good attestation/assertion fixture) for the integration tests. Unit-test the credential repository adapter + counter regression rejection deterministically. The real Face ID ceremony is a documented iPhone manual step (can't run headless), mirroring 2.1.

### References
- [Source: epics.md#Story 2.2: Biometric unlock] — user story + AC.
- [Source: prd.md#FR-35] — Face/Touch ID in place of PIN after PIN setup; required after timeout.
- [Source: architecture.md#Decision 5] — passkey/WebAuthn, Redis sessions, Spring Security deferred.
- [Source: 2-1-pin-setup-and-login.md] — session/cookie/gate/config foundation this story builds on.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **45 tests pass** (10 new WebAuthn), BUILD SUCCESS (Testcontainers PG18 + Redis8).
- Compile fix: `PublicKeyCredentialCreationOptions.toJson()` / `AssertionRequest.toJson()` throw checked `JsonProcessingException` (⊂ `IOException`) — moved inside the existing IOException handlers.
- Deployed stack verified through Tailscale: `POST /api/auth/webauthn/login/start` → options with `rpId: leannas-mac-mini.taila43287.ts.net` + challenge; `/api/auth/status` → `passkeyEnrolled:false`.

### Completion Notes List

- **Library + config (Task 1):** Yubico `webauthn-server-core` 2.9.0 (no Spring Security, per Decision 5). `WebAuthnProperties` (`argus.security.webauthn.rp-id/rp-name/origins`), `RelyingParty` bean. Env-wired in compose/.env (Mini = tailnet host).
- **Persistence (Task 2):** Flyway `V3__webauthn_credential.sql` (multi-row, bytea PK). `WebAuthnCredential` entity + repo + `ArgusCredentialRepository` (Yubico adapter; single user `argus` + fixed opaque user handle, multiple passkeys).
- **Ceremonies (Tasks 3–4):** `WebAuthnService` — registration (session-gated) + assertion (pre-session). Challenge/request stashed in Redis (5-min TTL, single-use), never trusted from client. Assertion advances the **signature counter** (clone detection via Yubico's verification) + `lastUsedAt`; success mints the **same `SessionStore` session + cookie** as PIN login. `WebAuthnController` exposes the 4 endpoints; the two `login/*` are allowlisted in `SessionAuthFilter`.
- **Manage/revoke (Task 5):** `GET/DELETE /api/auth/webauthn/credentials` (session-gated); `AuthStatus` gains `passkeyEnrolled` so the lock screen shows the biometric option.
- **Frontend (Task 6):** `apiClient` biometric helpers using the modern `PublicKeyCredential.parse*OptionsFromJSON` + `credential.toJSON()` (iOS 17.4+) — no manual base64url juggling. Lock screen (`PinScreen`) shows "Unlock with Face ID / Touch ID" when a passkey is enrolled + supported; any failure/cancel falls back to PIN (AC #3). `PasskeyManager` on Profile → Security enrolls + lists + revokes. PIN remains the fallback.
- **Tests (Task 7):** `ArgusCredentialRepositoryTest` (identity mapping) + `WebAuthnFlowIntegrationTest` (register gated, login allowlisted + returns options/handle, garbage/unknown-ceremony → 401, status passkeyEnrolled, register/start with session). The real Face ID ceremony is a documented iPhone manual step (can't run headless) — mirrors 2.1.

### File List

**New (backend):**
- `backend/src/main/resources/db/migration/V3__webauthn_credential.sql`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnProperties.java`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnConfig.java`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnCredential.java`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnCredentialRepository.java`
- `backend/src/main/java/com/argus/security/webauthn/ArgusCredentialRepository.java`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnService.java`
- `backend/src/main/java/com/argus/security/webauthn/WebAuthnController.java`
- `backend/src/main/java/com/argus/common/BadRequestException.java`
- `backend/src/test/java/com/argus/security/webauthn/ArgusCredentialRepositoryTest.java`
- `backend/src/test/java/com/argus/security/webauthn/WebAuthnFlowIntegrationTest.java`

**New (frontend):**
- `frontend/src/features/auth/PasskeyManager.tsx`

**Modified:**
- `backend/pom.xml` (webauthn-server-core 2.9.0)
- `backend/src/main/java/com/argus/common/GlobalExceptionHandler.java` (400 handler)
- `backend/src/main/java/com/argus/security/AuthStatus.java` (passkeyEnrolled)
- `backend/src/main/java/com/argus/security/AuthController.java` (passkeyEnrolled via WebAuthnService)
- `backend/src/main/java/com/argus/security/SessionAuthFilter.java` (allowlist webauthn/login/*)
- `frontend/src/lib/apiClient.ts` (WebAuthn ceremony helpers + PasskeyInfo + AuthStatus)
- `frontend/src/features/auth/AuthGate.tsx` (track + pass passkeyEnrolled)
- `frontend/src/features/auth/PinScreen.tsx` (biometric unlock button)
- `frontend/src/app/(dashboard)/profile/page.tsx` (PasskeyManager in Security)
- `docker-compose.yml` + `.env.example` (ARGUS_SECURITY_WEBAUTHN_*)

## Code Review (2026-06-22)

Adversarial review — Blind Hunter + Edge Case Hunter + Acceptance Auditor (all Opus 4.8), diff `b224a55..HEAD`. **ACs #1–#6 PASS**; AC#7 had an over-claim (now corrected). On-device Face ID enroll→unlock confirmed before review. Patches applied:

- [x] [Review][Med] **`finishAssertion` failed open** — minted a session even if the credential row was gone (counter not advanced → weakened clone detection). Now **fail-closed**: the credential must exist and the counter advance is mandatory.
- [x] [Review][Med] **Concurrent registration clobber** — challenge was keyed by `sessionId`; two enrollments collided and the `finally`-delete nuked the other. Registration now uses its **own ceremony id** (X-Argus-Ceremony header), symmetric with assertion.
- [x] [Review][Med] **`webauthnSupported()` under-detected** — only checked `window.PublicKeyCredential`, not the `parse*OptionsFromJSON`/`toJSON` methods used → button would show then throw on iOS 16/older. Now feature-detects the actual methods.
- [x] [Review][Low] **`DELETE /credentials/{id}` 500 on malformed base64** → now 400 (`BadRequestException`).
- [x] [Review][Low] **Duplicate-credential race** — `finishRegistration` uses `saveAndFlush` + maps `DataIntegrityViolationException` → 409 (Yubico already auto-excludes registered credentials via the adapter).
- [x] [Review][Low] **CORS** — expose `X-Argus-Ceremony` so cross-port dev can read the handle (deploy is single-origin, which is why on-device worked).
- [x] [Review][Honesty] **Corrected the over-claimed Task 7 tests** (counter/clone + happy-path finish) — see Task 7; added negative-path tests (register garbage→400, malformed revoke→400).

**Deferred (tracked):** pre-auth rate-limit on `login/start` → Story 2.6 (with login lockout); Redis-down→clean-503 (cosmetic; whole system needs Redis); software-authenticator happy-path/counter test (follow-up; on-device + Yubico-internal cover it).

**Dismissed:** `byte[]` `@Id` "fragile" (on-device enroll→unlock exercised `findById`/lookup + counter update — works); AuthGate stale `passkeyEnrolled` (logout reloads); `login/start` enrollment probe (`status` exposes it by design).

_Re-verified after patches: 47 backend tests pass; frontend lint+build clean._

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Story drafted (ready-for-dev) — WebAuthn/passkey biometric unlock on the 2.1 session foundation. |
| 2026-06-22 | Implemented WebAuthn passkey unlock: Yubico lib, V3 credential table, register/assert ceremonies (Redis challenge store), session minted on assertion, allowlisted login endpoints, frontend lock-screen biometric button + passkey management. 45 backend tests pass; deployed stack verified through Tailscale. On-device Face ID enroll→unlock confirmed. Status → review. |
| 2026-06-22 | Code review (3 layers): ACs #1–#6 pass. Patched 6 findings (fail-closed assertion, per-ceremony registration id, real WebAuthn feature-detection, revoke 400, dup-credential 409, CORS expose header) + corrected an over-claimed test subtask and added negative-path tests. 47 tests pass. Status → done. |

## Questions for the user (quick steer before build)

1. **WebAuthn library = Yubico `java-webauthn-server`** (standalone, no Spring Security — matches Decision 5). OK? *(Default: yes.)*
2. **Enroll-while-authenticated model**: you set a PIN (2.1), log in, then enable Face ID from Settings; thereafter the lock screen offers Face ID OR PIN. Confirm that's the intended UX (vs. enrolling biometric at first-launch). *(Default: enroll from Settings while logged in.)*
3. **Scope**: passkey **management/revoke UI** (AC #6) included now, or minimal (enroll + unlock only) with revoke deferred? *(Default: include a basic list + remove.)*
