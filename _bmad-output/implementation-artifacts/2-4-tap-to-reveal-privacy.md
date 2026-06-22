---
baseline_commit: 7dfacd1
---

# Story 2.4: Tap-to-reveal privacy

Status: done

## Story

As the user,
I want values hidden until tapped,
so that bystanders can't see my finances.

## Acceptance Criteria

1. **Hidden by default** — on load, sensitive values render as `••••••` (FR-36): total portfolio value, P&L figures, and Health Score numbers (the values present on this design branch; remaining surfaces adopt the same primitive as real data lands).
2. **Tap to reveal** — tapping any masked value reveals all sensitive values for the session; an eye toggle in the top bar reveals/hides all at once.
3. **Resets on lock** — reveal state is in-memory only, so it persists within the session and resets on lock (the lock/logout flow reloads the app, remounting the provider hidden).

## Tasks / Subtasks

- [x] Task 1 — Privacy state (AC: #2, #3)
  - [x] `PrivacyProvider` (context: `revealed`, `reveal`/`hide`/`toggle`), default hidden; in-memory so it resets on the lock reload. `usePrivacy` hook.
  - [x] Wrap the dashboard shell with `PrivacyProvider` (inside `AuthGate`).
- [x] Task 2 — Reusable primitives (AC: #1, #2)
  - [x] `<Sensitive>` — renders `••••••` (tappable → reveal all) until revealed, then the real children; takes a `className` so the mask matches the value's typography.
  - [x] `<PrivacyToggle>` — eye/eye-off button in the top bar.
- [x] Task 3 — Apply to sensitive values (AC: #1)
  - [x] Mask the total value + Health Score in `TopBar` (now a client component); the hero total value + day P&L in `PortfolioHero`; the Health Score number in `HealthScoreRing`.
- [x] Task 4 — Verify
  - [x] `npm run lint` + `npm run build` clean.

## Dev Notes

- Frontend-only (no backend, no secrets, no migration). The dashboard runs on mock data on this design branch; this story adds the masking mechanism + applies it to the prominent KPIs. [Source: prd.md#FR-36]
- Reset-on-lock is "free": `LogoutButton`/lock reloads the app (`window.location.reload()`), so the in-memory `revealed` flag returns to its default (hidden). No persistence by design.
- Privacy here is **cosmetic** (bystander protection), not access control — the real access control is the PIN/biometric session gate (2.1/2.2). [Source: prd.md#FR-36]
- No frontend test harness exists on this branch; verification is lint + build + visual/on-device, consistent with Stories 1.7/2.1/2.2 frontend work.

### References
- [Source: epics.md#Story 2.4] [Source: prd.md#FR-36]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8) — bmad-dev-story workflow

### Completion Notes List

- `PrivacyProvider` + `usePrivacy` (in-memory reveal flag, default hidden; resets on the lock reload).
- `<Sensitive>` masks any value as `••••••` (tap → reveal all) and renders children when revealed; `<PrivacyToggle>` eye button reveals/hides all from the top bar.
- Applied to: `TopBar` (total value + Health Score; converted to a client component), `PortfolioHero` (total value + day P&L), `HealthScoreRing` (score). Other value surfaces can adopt `<Sensitive>` as real data replaces the mock.
- Lint + build clean.

### File List

**New:** `frontend/src/features/privacy/PrivacyProvider.tsx`, `Sensitive.tsx`, `PrivacyToggle.tsx`
**Modified:** `frontend/src/app/(dashboard)/layout.tsx`, `components/shell/TopBar.tsx`, `components/dashboard/PortfolioHero.tsx`, `components/dashboard/HealthScoreRing.tsx`

## Code Review (2026-06-22)

Adversarial review + acceptance audit (Opus 4.8), diff `7dfacd1..HEAD`. ACs all pass; masking is leak-free (the real value is genuinely absent from the DOM when hidden). 2 findings patched:

- [x] [Review][High] **Reset-on-lock only fired on explicit logout, not on the Story 2.3 idle-timeout 401** — a silently-expired session left revealed values on screen (the exact FR-36 bystander case). Fix: `apiClient` now has a global 401 handler; `AuthGate` registers it and drops `authed → login` on any 401, which unmounts `PrivacyProvider` (resets reveal) **and** improves the 2.3 idle-lock UX (the app now re-locks on the next request instead of staying mounted).
- [x] [Review][Low] **Day-P&L arrow leaked gain/loss direction when masked** — the `▲/▼` rendered outside the mask. Fix: arrow + figure are now both inside `<Sensitive>`.

**Accepted nits (mock-data branch):** mask width ≠ value width (minor reflow on reveal); generic `aria-label="Tap to reveal"` on each mask. Not blocking.

_Re-verified: lint + build clean._

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented tap-to-reveal privacy (FR-36): PrivacyProvider + Sensitive + eye toggle, applied to the prominent KPIs. Lint+build clean. Status → review. |
| 2026-06-22 | Code review: ACs pass, leak-free. Patched 2 (idle-timeout 401 now re-locks + resets reveal via a global handler; P&L arrow no longer leaks direction when masked). Status → done. |
