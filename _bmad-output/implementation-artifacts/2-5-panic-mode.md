---
baseline_commit: 85145fc
---

# Story 2.5: Panic mode

Status: done

## Story

As the user,
I want an instant blank screen,
so that I can hide the app fast.

## Acceptance Criteria

1. **Gesture triggers it** — a configured gesture fires panic mode (FR-37): **long-press anywhere** (always on) and, optionally, **shake** (enabled in Settings; on iOS requests motion permission).
2. **Blanks immediately** — on trigger the screen is instantly covered by a **neutral** screen (a generic spinner — no Argus branding, no finances), synchronously, before any network call.
3. **Returns only after re-auth** — panic also **destroys the session**, so dismissing the cover reloads to the lock screen and re-entry requires PIN / Face ID. There is no way back into the app without re-auth.

## Tasks / Subtasks

- [x] Task 1 — Neutral cover (AC: #2)
  - [x] `PanicScreen` — fixed full-viewport neutral cover (innocuous spinner), top z-index, tap → dismiss.
- [x] Task 2 — Gestures (AC: #1)
  - [x] `usePanicGestures` — long-press (~600ms press-hold, cancelled by move/scroll/release) always on; shake via `devicemotion` when enabled (localStorage), threshold + cooldown.
- [x] Task 3 — Provider + session destruction (AC: #2, #3)
  - [x] `PanicProvider` — on panic: show the cover synchronously + `logout()` (destroy session); dismiss reloads → `AuthGate` lock screen (re-auth). Mounted in the authenticated shell only.
  - [x] Wire `PanicProvider` into the dashboard layout (inside `AuthGate`, wrapping `PrivacyProvider`).
- [x] Task 4 — Settings (AC: #1)
  - [x] `PanicSettings` (Profile → Security) — explains long-press; toggle to enable shake (requests iOS motion permission on enable); stored in localStorage.
- [x] Task 5 — Verify
  - [x] `npm run lint` + `npm run build` clean.

## Dev Notes

- Frontend-only. Panic reuses the existing auth machinery: it calls the 2.1 `logout()` (destroys the Redis session + clears the cookie), so "returns only after re-auth" is enforced by the **real session gate**, not a dismissible overlay. Dismiss = `window.location.reload()` → `AuthGate` sees no session → lock screen. [Source: prd.md#FR-37; security/AuthController logout]
- **Long-press = any pointer-down held ~600ms anywhere** (FR-37 says "long-press on any blank area"; implemented as anywhere — a deliberate, slightly broader reading so it works even over content). Normal taps (<600ms) and any move/scroll cancel it.
- **Shake** uses `DeviceMotionEvent`; iOS 13+ gates it behind `requestPermission()` which must come from a user tap — hence the Settings toggle requests it on enable. Off by default; long-press is the always-available trigger. Real shake behavior is only verifiable on a physical device.
- Gesture prefs live in localStorage (a device-local UI preference, like the theme), not the backend.
- No frontend test harness on this branch; verification = lint + build + on-device, consistent with 1.7/2.1/2.2/2.4.

### References
- [Source: epics.md#Story 2.5] [Source: prd.md#FR-37]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Completion Notes List

- `PanicProvider` (gesture-driven) → instant `PanicScreen` cover + `logout()`; dismiss reloads to the lock screen. Mounted inside `AuthGate` so gestures are active only when signed in.
- `usePanicGestures`: long-press (always) + opt-in shake (`devicemotion`, localStorage-gated).
- `PanicSettings` in Profile → Security: shake toggle with iOS motion-permission request.
- Lint + build clean. The actual gesture→blank→re-auth round-trip is a documented on-device check.

### File List

**New:** `frontend/src/features/panic/PanicProvider.tsx`, `PanicScreen.tsx`, `usePanicGestures.ts`, `PanicSettings.tsx`
**Modified:** `frontend/src/app/(dashboard)/layout.tsx`, `app/(dashboard)/profile/page.tsx`

## Code Review (2026-06-22)

Adversarial review + acceptance audit (Opus 4.8), diff `85145fc..HEAD`. All 3 ACs pass; 4 findings patched:

- [x] [Review][High] **Dismiss raced `logout()`** — a fast tap reloaded while the logout POST was in flight, aborting it, so the session could survive and `AuthGate` would let the user back in without re-auth (broke AC3). Fix: `dismiss` now awaits the logout promise (capped at 4s) before reloading. (Honest caveat added: a genuinely failed logout falls back to the 2.3 idle timeout.)
- [x] [Review][High] **Long-press over interactive controls triggered panic** — holding a button/field/checkbox for 600ms logged the user out. Fix: `onPointerDown` ignores presses originating on `button/a/input/textarea/select/label/[role=button]` — restoring FR-37's "blank area" intent.
- [x] [Review][Med] **Long-press timer not cancelled on focus loss** — added `blur` + `visibilitychange(hidden)` cancels so a press interrupted by an app switch can't fire later.
- [x] [Review][Med] **Shake toggle did nothing until reload** — `PanicSettings` now reloads on change so the listener (un)binds immediately.

**Accepted (Low, by design):** sensitive values remain in the DOM under the opaque cover (panic hides from eyes; session destruction is the real protection); double-fire is idempotent; first `devicemotion` event only seeds the baseline.

_Re-verified: lint + build clean._

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented panic mode (FR-37): long-press/shake → instant neutral cover + session destroy → re-auth to return. Settings toggle for shake. Lint+build clean. Status → review. |
| 2026-06-22 | Code review: 3 ACs pass. Patched 4 (await logout before reload; ignore interactive targets for long-press; cancel on blur/hidden; reload on shake toggle). Lint+build clean. Status → done. |
