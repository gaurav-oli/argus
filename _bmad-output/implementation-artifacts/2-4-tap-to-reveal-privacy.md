---
baseline_commit: 7dfacd1
---

# Story 2.4: Tap-to-reveal privacy

Status: review

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

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-22 | Implemented tap-to-reveal privacy (FR-36): PrivacyProvider + Sensitive + eye toggle, applied to the prominent KPIs. Lint+build clean. Status → review. |
