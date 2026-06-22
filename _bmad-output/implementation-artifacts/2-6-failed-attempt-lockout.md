---
baseline_commit: 237531e
---

# Story 2.6: Failed-attempt lockout

Status: review

## Story

As the user,
I want escalating lockouts,
so that brute force is deterred.

## Acceptance Criteria

1. **Escalating lockout (FR-38)** — consecutive failed PIN logins escalate: **3 → 30s**, **5 → 10m** (+ secondary-device alert), **10 → full lock** (clearable only from another signed-in device). The counter climbs across windows and **resets on a successful login**. Thresholds/durations are configurable (`argus.security.lockout.*`).
2. **Locked responses** — while locked, login is refused **before** the PIN is checked: a timed lockout returns **429** with `Retry-After` + `retryAfterSeconds`; a full lock returns **423** with `fullyLocked: true` (RFC 9457). No session is created, and even the correct PIN is refused while locked.
3. **Full-lock recovery from another device** — `POST /api/auth/lockout/clear` (session-gated) clears the lockout, so an already-signed-in device (e.g. another Tailscale device) recovers a full lock. Timed lockouts also auto-expire.
4. **Survives restart, shared across devices** — lockout state lives in Redis. The lock screen reflects an active lockout (`GET /api/auth/status` exposes `fullyLocked` + `lockoutSecondsRemaining`) and the login error shows the wait/“unlock from another device”.

## Tasks / Subtasks

- [x] Task 1 — Lockout core (AC: #1, #2)
  - [x] `LockoutProperties` (`argus.security.lockout.*`; FR-38 defaults). `LockedException` (full vs timed + retry-after). `LockoutService` on Redis: `assertNotLocked` / `recordFailure` (arms the matching lockout + throws at a threshold) / `reset` / `clear` / `current`.
- [x] Task 2 — Wire into login (AC: #1, #2)
  - [x] `AuthService.login`: `assertNotLocked()` first; on wrong/no PIN `recordFailure()` (may throw `LockedException`) then 401; on success `reset()`. Filled the `TODO(2.6)` seam.
- [x] Task 3 — Error mapping (AC: #2)
  - [x] `GlobalExceptionHandler` maps `LockedException` → 429 (timed, `Retry-After` header + `retryAfterSeconds`) / 423 (full, `fullyLocked`) RFC 9457.
- [x] Task 4 — Recovery + status (AC: #3, #4)
  - [x] `POST /api/auth/lockout/clear` (gated). `AuthStatus` gains `fullyLocked` + `lockoutSecondsRemaining`; `GET /status` reports them.
- [x] Task 5 — Frontend (AC: #2, #4)
  - [x] `PinScreen` shows lockout messages (429 → “try again in Ns”, 423 → “unlock from another signed-in device”). `apiClient` `AuthStatus`/`ProblemDetail` types extended.
- [x] Task 6 — Tests + verify (AC: all)
  - [x] `LockoutIntegrationTest` (Testcontainers): 3rd failure → 429 + Retry-After + retryAfterSeconds=30; lock refuses the correct PIN (no cookie); success resets the counter; 10th → 423 full + another device clears + recovery; clear requires a session. 62 tests pass; frontend lint+build clean.

## Dev Notes

- **Scope decision — lockout applies to the PIN path only, not biometric.** A 4–6 digit PIN is the brute-forceable factor; a WebAuthn assertion is a possession+inherence factor that isn't guessable, so biometric unlock stays available to the legitimate user even during a PIN lockout. (Documented for your awareness — flip easily by calling the lockout guard from `WebAuthnService` if you'd rather a full lock also block Face ID.)
- **Secondary-device alert at 5 failures (FR-38) is logged, not pushed** — Web Push is **Epic 8**; a `TODO(Epic 8)` + WARN log marks the spot. The 10-minute lockout itself is enforced now.
- Lockout is Redis-backed (`argus:auth:fails` counter + `argus:auth:lock` marker), so it survives restarts and is shared across devices — consistent with the session store. Counter increments only when **not** already locked, so escalation happens across successive windows (intended).
- Full-lock clear is **session-gated** (`SessionAuthFilter`), so only an authenticated device can clear it — that *is* "another Tailscale-connected device" (all devices share the one identity). Ties into Story 2.7 (remote session kill).

### References
- [Source: epics.md#Story 2.6] [Source: prd.md#FR-38] [Source: 2-1-…#login seam TODO(2.6)] [Source: architecture.md#Decision 5]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **62 tests pass** (+5 lockout), BUILD SUCCESS. Frontend lint+build clean.

### Completion Notes List

- `LockoutService` (Redis) + `LockoutProperties` + `LockedException`; escalating 3/5/10 → 30s/10m/full per FR-38, counter resets on success.
- Wired into `AuthService.login` (filled the `TODO(2.6)` seam): check-before, record-on-fail (throws at threshold), reset-on-success.
- `GlobalExceptionHandler`: 429 (+Retry-After) / 423 problem+json. `AuthStatus` + `/status` expose lockout; `POST /api/auth/lockout/clear` gated recovery.
- `PinScreen` renders lockout messages; types extended.

### File List

**New (backend):** `security/LockoutProperties.java`, `LockedException.java`, `LockoutService.java`, `test/.../security/LockoutIntegrationTest.java`
**Modified (backend):** `security/AuthService.java`, `AuthController.java`, `AuthStatus.java`, `common/GlobalExceptionHandler.java`
**Modified (frontend):** `lib/apiClient.ts`, `features/auth/PinScreen.tsx`

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented escalating failed-attempt lockout (FR-38): Redis-backed 3/5/10 → 30s/10m/full, 429/423 responses, gated clear-from-another-device, status + lock-screen messaging. 62 tests. Status → review. |
