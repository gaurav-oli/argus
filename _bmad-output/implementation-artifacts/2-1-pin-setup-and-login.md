---
baseline_commit: fef3f60
---

# Story 2.1: PIN setup and login

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to set a PIN on first launch and enter it to unlock Argus,
so that only I can open the platform and a server-side session protects the API.

## Acceptance Criteria

1. **First-launch detection** — `GET /api/auth/status` returns `{ pinSet, authenticated }` so the frontend can route to **setup** (no PIN yet), **login** (PIN set, no session), or **app** (valid session). Unauthenticated callers may reach only this endpoint + the auth endpoints + actuator health; everything else under `/api/**` returns **401** (RFC 9457 problem+json) without a valid session.
2. **PIN setup (first launch only)** — `POST /api/auth/pin` with a **4–6 digit numeric** PIN stores it as an **Argon2id hash** (never plaintext, never logged) and returns `201`. If a PIN already exists, it returns **409** (use the authenticated change-PIN path, out of scope here). A non-conforming PIN (wrong length / non-numeric) returns **400**.
3. **Login** — `POST /api/auth/login` with the correct PIN creates a **Redis-backed session** (opaque random id; session record in Redis with a TTL), sets it as an **HttpOnly, Secure, SameSite=Strict** cookie, and returns `200 { authenticated: true }`. **Given** first launch (no PIN), **When** a 4–6 digit PIN is set, **Then** it is stored as an Argon2 hash and a Redis session starts on correct entry.
4. **Incorrect PIN rejected** — a wrong PIN returns **401** (RFC 9457), creates **no** session, and reveals nothing about whether the failure was length/format vs mismatch beyond the status code. (Escalating lockout is **Story 2.6** — not implemented here; a `TODO(2.6)` hook/seam is left where the failed-attempt counter will live.)
5. **Session validation + logout** — a valid session cookie authorizes `/api/**`; `POST /api/auth/logout` deletes the Redis session (and clears the cookie) so the next call is unauthenticated. Session TTL defaults to **15 minutes** here; **Story 2.3** makes it user-configurable (leave the TTL sourced from one constant/property so 2.3 can override it).
6. **Verification** — unit tests for PIN validation + Argon2 hash/verify; integration tests (Testcontainers, matching the existing 1.2/1.5 pattern) for: setup→login→authorized call→logout→401, wrong-PIN→401, duplicate-setup→409, and unauthenticated `/api/**`→401. `docker compose --profile deploy up` still boots clean (Flyway V2 applies).

## Tasks / Subtasks

- [x] Task 1 — Persistence: credential table + entity (AC: #2)
  - [x] Flyway **`V2__app_credential.sql`** — single-row `app_credential` table: `id` (smallint PK, CHECK = 1 to enforce one row), `pin_hash text NOT NULL`, `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()`. snake_case, forward-only (never edit V1). [Source: db/migration/V1__baseline.sql header; architecture.md#Naming/Flyway]
  - [x] JPA entity `AppCredential` + Spring Data repository in `com.argus.security` (camelCase fields; `@Table("app_credential")`). `ddl-auto: validate` is on — the entity MUST match the migration exactly.
- [x] Task 2 — PIN hashing (AC: #2, #4)
  - [x] Add **`org.springframework.security:spring-security-crypto`** + **`org.bouncycastle:bcprov-jdk18on`** to `pom.xml` (Argon2 needs BouncyCastle). This pulls **only** the crypto module — **not** full Spring Security (Decision 5 keeps Spring Security deferred). Verify no `spring-boot-starter-security` is transitively added (it isn't from `-crypto`).
  - [x] `PinHasher` wrapping `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` (or explicit params). `hash(rawPin)` + `matches(rawPin, hash)` (constant-time via the encoder).
  - [x] PIN format guard (regex `^\d{4,6}$`) as a small validator used by both setup + login.
- [x] Task 3 — Redis session store (AC: #1, #3, #5)
  - [x] `SessionStore` on **`StringRedisTemplate`** (already wired — see `AgentEventPublisher`). Key `argus:session:{id}`; value = minimal JSON (`createdAt`, `lastSeenAt`); `id` = 256-bit `SecureRandom` base64url. `create()` sets the key with TTL; `validate(id)` returns presence (and may sliding-refresh TTL); `destroy(id)` deletes it.
  - [x] Session TTL from a single source: `argus.security.session-ttl` (default `15m`) bound via `@ConfigurationProperties` (`SecurityProperties`) — **Story 2.3 overrides this**. [Source: prd.md#FR-35 (15-min default); epics.md#Story 2.3]
- [x] Task 4 — Auth endpoints (AC: #1–#5)
  - [x] `AuthController` (`/api/auth`) in `com.argus.security`: `GET /status`, `POST /pin`, `POST /login`, `POST /logout`. Request records (`PinRequest`) with `jakarta.validation` (`@Pattern`). Responses typed; **never** echo the PIN.
  - [x] Cookie: name `ARGUS_SESSION`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, `Max-Age` = session TTL. Use `ResponseCookie`.
  - [x] Errors as **RFC 9457 `ProblemDetail`** via the existing `GlobalExceptionHandler` pattern — add `UnauthorizedException` (401) + `ConflictException` (409) + handlers; mirror `NotFoundException`/`handleNotFound`. [Source: common/GlobalExceptionHandler.java; common/NotFoundException.java]
- [x] Task 5 — Session gate for `/api/**` (AC: #1, #5)
  - [x] `OncePerRequestFilter` (`SessionAuthFilter`) reading the `ARGUS_SESSION` cookie → validate via `SessionStore`. **Allowlist** (no session required): `POST /api/auth/pin`, `POST /api/auth/login`, `GET /api/auth/status`, and `/actuator/health`. All other `/api/**` → 401 problem+json when absent/invalid.
  - [x] Register the filter so it runs for `/api/**` only (don't shadow static/WS). Keep it ordered before MVC. **Note:** the existing `/api/system-info` becomes session-gated — update any frontend pre-login code not to call it (pre-login should call only `/api/auth/status`).
- [x] Task 6 — Frontend: setup + lock screens (AC: #1, #3, #4)
  - [x] On load, call `GET /api/auth/status` (via the 1.6 `apiClient`) → route to **Set PIN** / **Enter PIN** / app. Numeric 4–6 digit entry matching the dark-premium tokens + the iPhone/PWA layout from Story 1.7. Wrong PIN shows an inline error (generic), not a stack trace.
  - [x] `apiClient` must send credentials (cookies): set `credentials: "include"` on fetches (single-origin so SameSite=Strict still sends them). Verify the 1.6 client and add it if missing. [Source: frontend/src/lib/apiClient.ts]
  - [x] Logout control wired to `POST /api/auth/logout` → returns to the lock screen.
- [x] Task 7 — Tests + verify (AC: #6)
  - [x] Unit: `PinHasher` (hash≠plaintext, matches true/false), PIN format validator (4/5/6 ok; 3/7/`12a4` rejected).
  - [x] Integration (Testcontainers Postgres + Redis, per 1.2/1.5): full happy path (setup→login→`/api/system-info` 200→logout→401), wrong-PIN 401, duplicate-setup 409, unauthenticated `/api/**` 401, status transitions.
  - [x] Build clean (`./mvnw -q verify`), frontend `npm run lint` + `build`, and `docker compose --profile deploy up` boots with V2 applied.

## Dev Notes

### Current state of the files this story touches [verified 2026-06-21, baseline fef3f60]
- **`com.argus.security`** — empty except `package-info.java`. All auth classes are **net-new** here. Test dir `src/test/java/com/argus/security` exists (`.gitkeep`). [Source: find backend/src]
- **Redis** — `StringRedisTemplate` is auto-wired and already used (`AgentEventPublisher`); `spring.data.redis.host/port` configured in `application.yml`. Reuse `StringRedisTemplate` for the session store — do **not** add a new connection factory. [Source: agent/AgentEventPublisher.java; application.yml#data.redis]
- **Errors** — `GlobalExceptionHandler` (`@RestControllerAdvice extends ResponseEntityExceptionHandler`) renders RFC 9457 `ProblemDetail`; `spring.mvc.problemdetails.enabled=true`. Add new exceptions + handlers in the same style (type URI `https://argus.local/problems/...`). [Source: common/GlobalExceptionHandler.java; application.yml]
- **Flyway** — only `V1__baseline.sql` (just `CREATE EXTENSION vector`). Add `V2__app_credential.sql`; never edit V1. `jpa.hibernate.ddl-auto=validate` so entity↔table must match. [Source: db/migration/V1__baseline.sql; application.yml]
- **REST pattern** — see `SystemInfoController` (`@RestController` + `@RequestMapping("/api/...")`, constructor injection, typed record responses). Match it. [Source: common/SystemInfoController.java]
- **CORS / WS** — `CorsConfig` + `WebSocketConfig` exist. The WS origin is currently wide-open (`setAllowedOriginPatterns("*")`) — that hardening is tracked for Epic 2 in `docs/epic-1-hardening-backlog.md` but is **NOT this story** (it pairs with WS session auth). Don't expand scope; just don't regress CORS for the single-origin deploy. [Source: docs/epic-1-hardening-backlog.md#Story 1.6]
- **pom** — no `spring-security-*` and no `springdoc` today; adding `spring-security-crypto` + BouncyCastle is the only new dependency surface. [Source: backend/pom.xml]
- **Frontend** — Story 1.7 shell + 1.6 `apiClient`/`wsClient` (env-driven, single-origin on the Mini). Add the lock/setup screens within the existing shell + tokens. [Source: 1-7-…md; 1-6-…md]

### Architecture requirements [Source: architecture.md#Decision 5/6]
- **Server-side sessions in Redis (not JWT)** — opaque id, instant revocation (enables FR-39 remote kill in Story 2.7). Don't introduce JWTs. [#Decision 5]
- **PIN as Argon2/BCrypt hash** — chose **Argon2id** (memory-hard; best practice for a low-entropy 4–6 digit PIN). [#Decision 5]
- **Spring Security is deferred** — implement custom auth; add only `spring-security-crypto` (the password encoders), **not** the starter/filter chain. [architecture.md L76; #Decision 5]
- **Errors = RFC 9457 Problem Details.** [#Decision 6]
- **Threat model:** Tailscale-only + FileVault + single user. No app-level column encryption. HTTPS is provided by `tailscale serve` (so `Secure` cookies work). [#Decision 5]

### Key design decisions (made here — flagged for your confirmation in §Questions)
1. **Argon2id** (not BCrypt) for the PIN hash — needs BouncyCastle. Stronger KDF; the real brute-force defense is the Story 2.6 lockout.
2. **Whole `/api/**` is session-gated** behind an allowlist (auth + actuator health). This affects every future story's endpoints (they're protected by default — good for a security epic) and makes the existing `/api/system-info` require a session.
3. **HttpOnly+Secure+SameSite=Strict cookie** carrying the session id (vs a JS-readable bearer token) — safest for the single-origin PWA; works because Tailscale provides HTTPS.

### Project Structure Notes
- New backend: `security/AppCredential.java`, `AppCredentialRepository.java`, `PinHasher.java`, `SessionStore.java`, `SecurityProperties.java`, `AuthController.java`, `SessionAuthFilter.java`, `dto/PinRequest.java`; `common/UnauthorizedException.java`, `ConflictException.java` (+ handlers in `GlobalExceptionHandler`). New resource: `db/migration/V2__app_credential.sql`.
- New frontend: lock/setup screen components within the 1.7 shell; `apiClient` `credentials:"include"`.
- Naming: snake_case DB, camelCase Java/JSON/TS; `PascalCase` classes; package `com.argus.security`. [Source: architecture.md#Naming]
- Keep the dev workflow intact: `docker compose up` (no profile) stays DBs-only; the app runs via `--profile deploy` or `mvn spring-boot:run`.

### Latest-tech guidance (stable as of 2026-06)
- **Argon2 in Spring Security Crypto**: `Argon2PasswordEncoder` requires BouncyCastle on the classpath (`org.bouncycastle:bcprov-jdk18on`). Prefer `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` for sane params, or set memory/iterations/parallelism explicitly. Importing `spring-security-crypto` alone does **not** activate the Spring Security filter chain.
- **Spring Boot 4 / Java 25**: use `ResponseCookie` for `SameSite`; register the filter via a `FilterRegistrationBean` scoped to `/api/*`, or a `OncePerRequestFilter` with an internal path check. `jakarta.validation` `@Pattern` for the PIN (validation starter already present).
- **Redis**: `StringRedisTemplate.opsForValue().set(key, json, Duration)` for TTL; `getExpire`/`expire` for sliding refresh; `delete` for logout/kill.

### Testing standards
- Match the existing **Testcontainers** integration pattern from Stories 1.2 (Postgres + Flyway) and 1.5 (Redis). Spin Postgres + Redis; assert Flyway V2 applied; exercise the auth flow over MockMvc/WebTestClient including the cookie round-trip. Unit-test `PinHasher` + the PIN validator with no infra. No PIN or hash may appear in logs/assertions output.

### References
- [Source: epics.md#Story 2.1: PIN setup and login] — user story + AC.
- [Source: epics.md#Epic 2: Security & Access] — epic scope (2.2 biometrics, 2.3 timeout, 2.6 lockout, 2.7 remote kill depend on this session foundation).
- [Source: prd.md#FR-35] — PIN 4–6 digits; required first launch + after timeout; 15-min default timeout.
- [Source: prd.md#FR-38] — escalating lockout (Story 2.6; leave a seam).
- [Source: prd.md#FR-39] — remote session kill (Story 2.7; enabled by Redis sessions).
- [Source: architecture.md#Decision 5 — Authentication & Security] — Redis sessions, Argon2/BCrypt, Spring Security deferred, Tailscale+FileVault threat model.
- [Source: architecture.md#Decision 6 — API & Communication] — RFC 9457 errors.
- [Source: backend/src/main/java/com/argus/common/GlobalExceptionHandler.java] — error style to mirror.
- [Source: backend/src/main/java/com/argus/agent/AgentEventPublisher.java] — `StringRedisTemplate` usage to reuse.
- [Source: backend/src/main/resources/application.yml] — redis/datasource/flyway/problemdetails config.
- [Source: docs/epic-1-hardening-backlog.md#Story 1.6] — WS origin restriction (related Epic 2 work, NOT this story).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **Tests run: 34, Failures: 0, Errors: 0**, BUILD SUCCESS (Testcontainers Postgres 18 + Redis 8).
- One issue found + fixed during the run: Spring Boot 4 ships **Jackson 3** (`tools.jackson.databind`), so injecting `com.fasterxml.jackson.databind.ObjectMapper` into the filter failed context load. Fix: `SessionAuthFilter` now writes its 401 problem+json directly (no ObjectMapper dependency, version-proof).
- Frontend `npm run lint` clean (after restructuring the `AuthGate` mount-fetch to satisfy the new `react-hooks/set-state-in-effect` rule), `npm run build` ✓.
- Deployed stack (`docker compose --profile deploy up -d --build`): all 4 containers healthy; `flyway_schema_history` = V1 baseline + **V2 app_credential** (both success); `GET /api/system-info` without a session → **401**; `GET /api/auth/status` → `{"pinSet":false,"authenticated":false}`.

### Completion Notes List

- **Persistence (Task 1):** `V2__app_credential.sql` — single-row table (`CHECK (id = 1)`), `pin_hash text`. `AppCredential` entity + `AppCredentialRepository` (`findSingleton`/`existsSingleton`). Passes `ddl-auto: validate`.
- **Hashing (Task 2):** added `spring-security-crypto` + `bcprov-jdk18on` (encoders only — **not** the Spring Security starter, per Decision 5). `PinHasher` = Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`); `Pin` centralizes the `^\d{4,6}$` rule.
- **Sessions (Task 3):** `SessionStore` on `StringRedisTemplate` — opaque 256-bit id, key `argus:session:{id}`, TTL = `argus.security.session-ttl` (default 15m via `SecurityProperties`; `validate()` slides the TTL). Story 2.3 overrides the one property.
- **Endpoints (Task 4):** `AuthController` (`/api/auth/status|pin|login|logout`); `PinRequest` `@Pattern`; `AuthStatus`; `AuthService` holds the rules (`ConflictException` 409 on duplicate setup, `UnauthorizedException` 401 on wrong/no PIN; a `TODO(2.6)` seam marks the lockout point). New `UnauthorizedException`/`ConflictException` + handlers added to `GlobalExceptionHandler` (RFC 9457). `SessionCookie` = HttpOnly + Secure + SameSite=Strict.
- **Gate (Task 5):** `SessionAuthFilter` registered for `/api/*` only; allowlists `GET /api/auth/status`, `POST /api/auth/login`, `POST /api/auth/pin`; everything else needs a valid session. `/actuator/health` is outside `/api/*` so it stays open. `CorsConfig` gained `allowCredentials(true)` for the cross-port dev case (Mini is single-origin).
- **Frontend (Task 6):** `apiClient` now sends `credentials: "include"` + `apiPost` + auth calls. `AuthGate` (wraps the dashboard layout) checks `/api/auth/status` on mount → setup / lock / app. `PinScreen` (setup with confirm step, or login) styled with the §12 tokens. `LogoutButton` on the profile page → `logout()` + reload.
- **Tests (Task 7):** `PinTest`, `PinHasherTest` (unit); `AuthFlowIntegrationTest` (Testcontainers) — full happy path (setup→login→authorized→logout→401), wrong-PIN 401, duplicate-setup 409, invalid-format 400, unauthenticated `/api/**` 401.
- **Decisions confirmed by user before dev:** Argon2id; gate all `/api/**`; change/forgot-PIN out of scope.
- **On-device fix (found during iPhone testing):** same-origin browser POSTs failed with **403** on the Mini. Cause: behind `tailscale serve`, Spring sees the request landing on `127.0.0.1` while Safari attaches `Origin: https://…ts.net`, so it treats it as a cross-origin request and rejected it against the hardcoded `localhost:3000` CORS allowlist. Fix: made CORS origins env-configurable (`argus.web.allowed-origins` → `WebProperties`; `ARGUS_WEB_ALLOWED_ORIGINS` in compose/.env, set to the tailnet host on the Mini) — this also delivers the Epic-1 hardening-backlog "env-configurable allowed origins" item that Story 2.2's WebAuthn/WS work will reuse. `PinScreen` error handling also hardened (distinguishes setup vs sign-in failure, self-heals on 409, surfaces the HTTP status). **Verified on a real iPhone over Tailscale: set PIN → confirm → dashboard → lock & sign out → re-enter.**

### File List

**New (backend):**
- `backend/src/main/resources/db/migration/V2__app_credential.sql`
- `backend/src/main/java/com/argus/security/AppCredential.java`
- `backend/src/main/java/com/argus/security/AppCredentialRepository.java`
- `backend/src/main/java/com/argus/security/Pin.java`
- `backend/src/main/java/com/argus/security/PinHasher.java`
- `backend/src/main/java/com/argus/security/SecurityProperties.java`
- `backend/src/main/java/com/argus/security/SessionStore.java`
- `backend/src/main/java/com/argus/security/SessionCookie.java`
- `backend/src/main/java/com/argus/security/PinRequest.java`
- `backend/src/main/java/com/argus/security/AuthStatus.java`
- `backend/src/main/java/com/argus/security/AuthService.java`
- `backend/src/main/java/com/argus/security/AuthController.java`
- `backend/src/main/java/com/argus/security/SessionAuthFilter.java`
- `backend/src/main/java/com/argus/security/SecurityConfig.java`
- `backend/src/main/java/com/argus/common/UnauthorizedException.java`
- `backend/src/main/java/com/argus/common/ConflictException.java`
- `backend/src/test/java/com/argus/security/PinTest.java`
- `backend/src/test/java/com/argus/security/PinHasherTest.java`
- `backend/src/test/java/com/argus/security/AuthFlowIntegrationTest.java`

**New (frontend):**
- `frontend/src/features/auth/AuthGate.tsx`
- `frontend/src/features/auth/PinScreen.tsx`
- `frontend/src/features/auth/LogoutButton.tsx`

**New (cont.):**
- `backend/src/main/java/com/argus/config/WebProperties.java` (env-configurable CORS origins)

**Modified:**
- `backend/pom.xml` (spring-security-crypto + BouncyCastle)
- `backend/src/main/java/com/argus/common/GlobalExceptionHandler.java` (401/409 handlers)
- `backend/src/main/java/com/argus/config/CorsConfig.java` (allowCredentials + env-driven origins)
- `frontend/src/lib/apiClient.ts` (credentials + POST + auth calls)
- `frontend/src/app/(dashboard)/layout.tsx` (wrap in AuthGate)
- `frontend/src/app/(dashboard)/profile/page.tsx` (LogoutButton)
- `frontend/src/features/auth/PinScreen.tsx` (hardened error handling — added post-implementation)
- `docker-compose.yml` (ARGUS_WEB_ALLOWED_ORIGINS passthrough)
- `.env.example` (ARGUS_WEB_ALLOWED_ORIGINS documented)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-21 | Story drafted (ready-for-dev) — context-engineered from epics/PRD/architecture + current backend patterns. |
| 2026-06-21 | Implemented PIN setup + login: Flyway V2 credential table, Argon2id hashing, Redis session store + HttpOnly/Secure/SameSite cookie, `/api/auth/*` endpoints, `/api/**` session gate, and the frontend AuthGate/PinScreen/logout. 34 backend tests pass; frontend lint+build clean; deploy stack verified (V2 applied, gate enforces 401). Status → review. |
| 2026-06-21 | On-device testing (iPhone/Tailscale) surfaced a 403 on same-origin POSTs behind the reverse proxy. Fixed via env-configurable CORS origins (`argus.web.allowed-origins`/`WebProperties`, `ARGUS_WEB_ALLOWED_ORIGINS`) set to the tailnet host; hardened PinScreen error handling. Re-verified: 34 tests pass; full PIN flow works on a real iPhone. |

## Questions for the user (confirm before/at dev time — non-blocking; sensible defaults chosen)

1. **PIN hash = Argon2id** (adds a BouncyCastle dep). OK, or prefer **BCrypt** (no extra dep, slightly simpler)? Either satisfies Decision 5. *(Default: Argon2id.)*
2. **Gate all `/api/**` behind a session now** (so `/api/system-info` needs auth too)? This is the right posture for a security epic but changes existing behavior. *(Default: yes, with the auth + actuator-health allowlist.)*
3. **Change-PIN / forgot-PIN** is **out of scope** for 2.1 (setup is first-launch-only; duplicate setup → 409). Confirm that's fine for now. *(Default: out of scope.)*
