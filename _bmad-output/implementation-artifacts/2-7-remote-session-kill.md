---
baseline_commit: d6c29ad
---

# Story 2.7: Remote session kill

Status: review

## Story

As the user,
I want to end a session from another device,
so that I can secure a lost device.

## Acceptance Criteria

1. **List active sessions** — `GET /api/auth/sessions` (session-gated) returns every active session with a device label, created + last-active times, and a `current` flag for the caller's own. Sessions are identified by a non-reversible **handle** (truncated SHA-256 of the id) so raw session tokens never reach the browser.
2. **Remote kill** — `DELETE /api/auth/sessions/{handle}` (session-gated) terminates the target session. **Given** an active session, **When** I trigger kill from another Tailscale device, **Then** the target terminates within 5s — the session is destroyed in Redis immediately and the target is rejected on its very next request (the filter validates every call), with the 2.4 global-401 handler dropping it to the lock screen.
3. **Settings UI** — Profile → Security lists sessions (device + last active + “This device”) with an End-session / Sign-out control; killing the current session reloads to the lock screen.

## Tasks / Subtasks

- [x] Task 1 — Session metadata (AC: #1)
  - [x] `SessionStore` stores each session as a Redis **hash** (`created`, `seen`, `device`) instead of a marker string; `validate` refreshes `seen` + slides the TTL; `create(device)` records the device label (+ a no-arg overload for internal/test use).
- [x] Task 2 — List + revoke by handle (AC: #1, #2)
  - [x] `list(currentId)` scans the session keyspace → `SessionInfo` (handle, device, createdAt, lastActiveAt, current). `revokeByHandle(handle)` matches by recomputed SHA-256 handle and deletes. Handles are non-reversible so tokens aren't exposed.
- [x] Task 3 — Endpoints (AC: #1, #2)
  - [x] `GET /api/auth/sessions` + `DELETE /api/auth/sessions/{handle}` on `AuthController` (session-gated — not allowlisted). `DeviceLabel.from(User-Agent)` labels sessions at login; threaded through `AuthService.login(pin, device)` and `WebAuthnService.finishAssertion(…, device)`.
- [x] Task 4 — Frontend (AC: #3)
  - [x] `SessionManager` (Profile → Security): lists sessions, remote-kill button, current-session reloads to lock. `apiClient` `listSessions`/`revokeSession` + `SessionInfo`.
- [x] Task 5 — Tests + verify (AC: all)
  - [x] `SessionManagementIntegrationTest` (Testcontainers): two logins (iPhone + Mac) listed with labels + exactly one `current`; laptop kills the phone handle → phone’s next gated call 401, list drops to 1; endpoints require a session. 65 tests pass; frontend lint+build clean.

## Dev Notes

- **5-second target is met trivially**: kill is an immediate Redis delete, and `SessionAuthFilter` validates every `/api/**` request, so the killed device is rejected on its next call (then the Story 2.4 global-401 handler re-gates it to the lock screen). No polling needed.
- **Handles, not tokens**: the list/revoke API exposes `SHA-256(id)[:16]`, never the raw session id (which is the cookie secret) — so even though the owner controls all sessions, a frontend bug/XSS can't exfiltrate live tokens.
- **Session model change**: sessions moved from a string marker to a Redis hash to carry metadata. TTL/slide/Never semantics (Story 2.3) and the lockout reconcile (2.6) are unchanged (they operate on the key, type-agnostic). `last-seen` updates each request (a write per call — fine at single-user scale).
- **Keyspace scan**: `list`/`revoke` use `keys(argus:session:*)` — acceptable for a single user’s handful of sessions; revisit with a SCAN/index only if session counts ever grow.
- Ties to 2.6: the full-lock clear endpoint and this kill are both "another signed-in device" recovery actions on the shared single identity.

### References
- [Source: epics.md#Story 2.7] [Source: prd.md#FR-39] [Source: architecture.md#Decision 5 — Redis sessions enable instant revocation] [Source: 2-4-…#global 401 handler]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **65 tests pass** (+2), BUILD SUCCESS. Frontend lint+build clean.

### Completion Notes List

- `SessionStore` now hash-backed with metadata; `create(device)` + `list`/`revokeByHandle` using non-reversible handles.
- `GET/DELETE /api/auth/sessions[/{handle}]` (gated); `DeviceLabel` from User-Agent threaded through PIN + biometric login.
- `SessionManager` UI in Profile → Security (list + remote kill; current → reload to lock).
- `SessionManagementIntegrationTest` proves list + cross-device kill + gating.

### File List

**New (backend):** `security/DeviceLabel.java`, `test/.../security/SessionManagementIntegrationTest.java`
**Modified (backend):** `security/SessionStore.java` (hash model + list/revoke), `AuthService.java` (login device), `AuthController.java` (sessions endpoints + UA), `security/webauthn/WebAuthnService.java` + `WebAuthnController.java` (assertion device)
**New (frontend):** `features/auth/SessionManager.tsx`
**Modified (frontend):** `lib/apiClient.ts`, `app/(dashboard)/profile/page.tsx`

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented remote session kill (FR-39): hash-backed sessions with device metadata, list + revoke-by-handle endpoints, SessionManager UI. Kill is instant in Redis; target rejected on next request. 65 tests. Status → review. |
