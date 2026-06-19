---
baseline_commit: 9bbcac8
---

# Story 1.7: Dark-theme dashboard shell

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want the Argus shell (theme, layout, navigation),
so that the app looks and navigates like the spec.

## Acceptance Criteria

1. **Given** the app on **desktop** (≥1280px), **Then** the **dark-premium** theme tokens are applied and the layout shows a fixed **left sidebar nav + top bar** (Health Score + total value placeholders, always visible) + **main content** + **right panel** (live alerts/recommendations placeholder) + **bottom strip** (agent status + RAM/SSD/cost placeholders) — per §12 Layout.
2. **Given** the app on **mobile** (iPhone/iPad, ≤767px), **Then** a **5-tab bottom nav** (Home, Portfolio, Intelligence, Agents, Profile) + full-width stacked content renders; the sidebar/right-panel/bottom-strip collapse away responsively (tablet 768–1024 and desktop 1280+ behave sensibly).
3. **Given** any view, **Then** **Inter** (primary) and **JetBrains Mono** (mono/technical) are applied, and the §12 color tokens are available as theme utilities.
4. **Given** content is loading, **Then** **skeleton** placeholders are shown (a reusable `Skeleton` component, demonstrated in at least one panel / a route `loading` state).
5. **Given** navigation, **Then** the 5 routes exist under a dashboard route group (`/`, `/portfolio`, `/intelligence`, `/agents`, `/profile`) as stub pages, the active nav item is highlighted, and a **web app manifest** makes the PWA installable. *(Service worker + Web Push are deferred to the notifications story, Epic 8.)*

## Tasks / Subtasks

- [x] Task 1 — Theme tokens + typography (AC: #1, #3)
  - [x] Rewrite `src/app/globals.css`: Tailwind v4 `@theme` with the §12 tokens — `--color-background:#0A0A0F`, `--color-surface:#13131A`, `--color-border:#1E1E2E`, `--color-accent:#00D4FF`, `--color-gains:#00FF88`, `--color-losses:#FF3B5C`, `--color-warning:#FFB800`, `--color-text-primary:#E8E8F0`, `--color-text-secondary:#6B7280`; map `--font-sans`/`--font-mono`. Base `body` = background + text-primary, dark always.
  - [x] `src/app/layout.tsx`: swap Geist → `Inter` + `JetBrains_Mono` via `next/font/google` (self-hosted at build — works offline); set CSS vars; `<html lang="en" class="dark ...">`; title "Argus".
- [x] Task 2 — Shell layout components (AC: #1, #2)
  - [x] `src/components/ui/Skeleton.tsx` (animated pulse block) + a small `cn()` util in `src/lib/utils.ts` (clsx + tailwind-merge — the shadcn convention so real shadcn components drop in later).
  - [x] `src/components/shell/` : `Sidebar.tsx` (fixed left nav, desktop), `TopBar.tsx` (brand + Health Score + total value placeholders), `RightPanel.tsx` (alerts/recs placeholder), `BottomStrip.tsx` (agent status + RAM/SSD/cost placeholders), `BottomNav.tsx` (mobile 5-tab). Nav items share one source (`navItems` array: Home/Portfolio/Intelligence/Agents/Profile + hrefs + icons).
  - [x] Active-route highlight via `usePathname()` (these are client components).
- [x] Task 3 — Dashboard route group + pages (AC: #2, #5)
  - [x] `src/app/(dashboard)/layout.tsx`: composes the shell — sidebar/right-panel/bottom-strip visible at `lg:`/`xl:` and hidden on mobile; `BottomNav` visible only on mobile (`lg:hidden`); main content always. Responsive via Tailwind breakpoints (md/lg/xl ≈ tablet/desktop).
  - [x] Stub pages: `(dashboard)/page.tsx` (Home — a "Morning Briefing" placeholder card), `(dashboard)/portfolio/page.tsx`, `(dashboard)/intelligence/page.tsx`, `(dashboard)/agents/page.tsx`, `(dashboard)/profile/page.tsx`. Each: a titled placeholder using surface cards + a `Skeleton` demo.
  - [x] Remove the default `create-next-app` `page.tsx` content (the marketing splash) — the dashboard Home replaces it. Delete `public/*.svg` demo assets if referenced.
- [x] Task 4 — PWA manifest (AC: #5)
  - [x] `src/app/manifest.ts` (Next metadata route) — name "Argus", short_name, theme/background `#0A0A0F`, display `standalone`, start_url `/`, icons (use a simple placeholder icon in `public/`). Makes it installable. (No service worker yet.)
- [x] Task 5 — Verify build + render (AC: all)
  - [x] `npm run build` succeeds (type-check + lint clean).
  - [x] `npm run dev`; `curl` each of `/`, `/portfolio`, `/intelligence`, `/agents`, `/profile` → 200 and HTML contains the nav labels; confirm the dark background token and Inter font are referenced. Stop cleanly.
  - [x] Note: pixel-level visual review (looks "dark-premium") is for the user in a browser — headless verification covers structure + build only.

### Review Findings

_Code review 2026-06-19 (Blind Hunter + Edge Case Hunter + Acceptance Auditor, all Opus 4.8). No Critical/High acceptance violations; all 5 ACs and all 9 §12 color tokens verified exact. 2 decisions, 9 patches, 8 dismissed as noise._

**Decisions (resolved 2026-06-19):**
- [x] [Review][Decision] Responsive breakpoint strategy — **RESOLVED: keep as-is** (lg/xl mapping). This follows §12's explicit Tailwind hint ("`<lg` = mobile/stacked, `lg`/`xl` = desktop"); a dedicated tablet (768–1024) layout and lowering the RightPanel below `xl` are tracked for a later UI-polish pass, out of scope for this shell story. [layout.tsx] (edge+auditor)
- [x] [Review][Decision] iOS PWA install icon — **RESOLVED: generated placeholder PNGs now.** Rendered the brand SVG to `public/icon-192.png` + `public/icon-512.png` (incl. `maskable`) and added `src/app/apple-icon.png` (Next auto-emits the `apple-touch-icon` link). Real branding still arrives with the Epic 8 PWA story. [manifest.ts, apple-icon.png] (edge+auditor)

**Patches (all applied 2026-06-19):**
- [x] [Review][Patch] Skeleton `animate-pulse` ignores `prefers-reduced-motion` — added a global `@media (prefers-reduced-motion: reduce)` reset in globals.css (neutralizes all animation/transition for motion-sensitive users) [globals.css] (edge)
- [x] [Review][Patch] Skeleton a11y — placeholder now `aria-hidden` by default (decorative); `loading.tsx` wraps with `role="status"` + `aria-busy` + sr-only "Loading…" [Skeleton.tsx, loading.tsx] (edge)
- [x] [Review][Patch] Mobile nav labels — wrapped each label in `whitespace-nowrap`; icon `shrink-0`; nav uses `min-h-16` so single-line labels stay aligned at 375px [BottomNav.tsx] (edge)
- [x] [Review][Patch] `h-screen` → `h-dvh` so mobile dynamic toolbars don't clip content under `overflow-hidden` [layout.tsx] (edge)
- [x] [Review][Patch] BottomNav `env(safe-area-inset-bottom)` padding added; enabled via `viewportFit: "cover"` in the viewport export [BottomNav.tsx, layout.tsx] (blind)
- [x] [Review][Patch] Added Next 16 `viewport` export — `themeColor: #0A0A0F` + `colorScheme: dark` (dark browser chrome) [layout.tsx] (edge)
- [x] [Review][Patch] `cn()` now uses `extendTailwindMerge` taught the §12 `@theme` color tokens so conflicting custom utilities resolve correctly [lib/utils.ts] (blind)
- [x] [Review][Patch] Added trailing newlines [globals.css, layout.tsx] (blind)
- [x] [Review][Patch] Removed redundant hand-rolled `.tabular-nums` (Tailwind v4 ships it) [globals.css] (auditor)

**Dismissed as noise (8):** `font-mono` no-op on agents skeleton (blind); always-`dark` class flagged "dead" — intentional single-theme convention (blind); `isActive` trailing-slash edge — Next normalizes trailing slashes (edge); `isActive` deep-nesting false-positive — speculative, no such routes (edge); BottomNav 6th-tab tap-target — speculative (edge); TopBar/BottomStrip nesting — spec-compliant interpretation (auditor); bare `aria-hidden` prop — confirmed valid (blind); "large tabular numbers not demonstrated" — expected, real numbers arrive with feature stories (auditor).

## Dev Notes

### §12 UI Design Constraints (the authority for this story) [Source: prd.md §12]
- **Colors** (exact hex): Background `#0A0A0F`, Surface `#13131A`, Border `#1E1E2E`, Accent `#00D4FF`, Gains `#00FF88`, Losses `#FF3B5C`, Warning `#FFB800`, Text-primary `#E8E8F0`, Text-secondary `#6B7280`.
- **Type:** Inter — headlines Bold 24–32px, large numbers Bold 28–48px **tabular**, body Regular 14–16px, labels Medium 11–12px uppercase. JetBrains Mono — agent logs / technical data.
- **Desktop layout:** fixed left sidebar nav + top bar (Health Score + total value always visible) + main + right panel (live alerts + recommendations) + bottom strip (agent status + RAM/SSD/cost).
- **Mobile (PWA):** bottom tab bar, 5 tabs: Home, Portfolio, Intelligence, Agents, Profile + full-width stacked cards.
- **Breakpoints:** mobile 375–390, tablet 768–1024, desktop 1280+. (Tailwind: treat `<lg` as mobile/stacked + bottom-nav, `lg`/`xl` as the full desktop shell.)

### Architecture requirements
- **Frontend stack (Decision 7):** Next 16 App Router + Tailwind v4 + TanStack Query (server state) + Zustand (UI state) + shadcn/ui + TradingView Lightweight Charts; PWA service worker + Web Push. This story builds the **static shell only** — data fetching (TanStack Query), state (Zustand), charts, service worker, and Web Push come with their feature stories. Don't wire them now; just leave the `stores/`, `hooks/` folders ready. [Source: architecture.md#Decision 7]
- **Folder conventions:** `src/features/{...}` per domain, shared `src/components`, `src/lib`, `src/hooks`, `src/stores`; components `PascalCase.tsx`. [Source: architecture.md#Structure Patterns; #Naming Patterns]
- **Routes mapping:** the 5 tabs map to the monorepo tree's `app/(dashboard)` routes (home, portfolio, intelligence, agents, profile). [Source: architecture.md#Complete Monorepo Tree]

### Verified setup notes
- `create-next-app` set up **Tailwind v4** (CSS-first: `@import "tailwindcss"` + `@theme` in `globals.css`; no `tailwind.config.js`) and **next/font** (currently Geist). Swap the fonts, keep the next/font self-hosting approach (no CDN — important for the offline-capable Mini). [Verified by reading the scaffolded files 2026-06-18]
- The 1.6 `lib/apiClient.ts` + `lib/wsClient.ts` exist but are NOT consumed yet — this shell is static; the Home "live" bits get wired when their features land. Keep using them only when a real data need exists.
- shadcn full `init` is **not** required for the shell; add the `cn()` util + tokens now so shadcn components can be added per-feature later. Avoid running interactive `npx shadcn init` headless. [Scope decision]

### Scope decisions
- **Static shell only** — placeholders, no real data, no WebSocket subscription, no charts. The point is theme + layout + navigation + skeletons + installable manifest.
- **Service worker / Web Push deferred** to the notifications story (Epic 8 / FR-17). The manifest alone makes it installable; full offline SW is later.
- No test framework is set up on the frontend (create-next-app adds none). This story verifies via `npm run build` + dev-server `curl` structural checks. Do not add a frontend test harness here (its own future task); a Playwright/RTL setup is out of scope.

### Source tree this story touches
```
frontend/src/
├── app/
│   ├── layout.tsx                       # UPDATE (Inter + JetBrains Mono, dark <html>, title)
│   ├── globals.css                      # UPDATE (§12 tokens via @theme)
│   ├── manifest.ts                      # NEW (PWA manifest)
│   ├── page.tsx                         # REMOVE/replace (moves into (dashboard))
│   └── (dashboard)/
│       ├── layout.tsx                   # NEW (shell composition, responsive)
│       ├── page.tsx                     # NEW (Home — briefing placeholder)
│       ├── portfolio/page.tsx           # NEW
│       ├── intelligence/page.tsx        # NEW
│       ├── agents/page.tsx              # NEW
│       └── profile/page.tsx             # NEW
├── components/
│   ├── ui/Skeleton.tsx                  # NEW
│   └── shell/{Sidebar,TopBar,RightPanel,BottomStrip,BottomNav,navItems}.tsx  # NEW
└── lib/utils.ts                         # NEW (cn helper)
public/  (icon for manifest)             # NEW (placeholder icon) ; remove unused demo svgs
```

### Testing standards
- `npm run build` is the gate (Next runs type-check + ESLint). Then dev-server `curl` each route for 200 + nav labels present in SSR HTML. No unit tests this story (no FE test harness yet — deferred).

### Project Structure Notes
- First substantial frontend work since the 1.1 scaffold + 1.6 lib clients. Prior backend stories 1.1/1.2 done; 1.4/1.5/1.6 committed (status review); 1.3 deferred. Continue on a new branch off `9bbcac8`.
- `components/`, `hooks`, `stores` currently hold only `.gitkeep` — this story fills `components/`; `hooks`/`stores` stay empty until features need them (leave their `.gitkeep`).

### References
- [Source: epics.md#Story 1.7: Dark-theme dashboard shell] — user story + ACs (UX-DR3 desktop layout, UX-DR4 mobile 5-tab; these map to §12 Layout).
- [Source: prd.md §12 UI Design Constraints] — colors, typography, layout, breakpoints (the design authority).
- [Source: architecture.md#Decision 7 — Frontend Architecture] — Next/Tailwind/TanStack/Zustand/shadcn/PWA (most deferred to features).
- [Source: architecture.md#Complete Monorepo Tree] — `app/(dashboard)` routes + `features/components/lib/hooks/stores`.
- [Source: 1-6-rest-websocket-round-trip.md] — existing `lib/apiClient.ts`/`wsClient.ts` (not consumed yet).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `npm run build` (Next.js 16.2.9 / Turbopack): compiled successfully, TypeScript + ESLint clean, all 5 routes + `/manifest.webmanifest` prerendered as static content.
- Dev-server `curl` checks: `/`, `/portfolio`, `/intelligence`, `/agents`, `/profile` → 200; `/manifest.webmanifest` → 200.
- SSR HTML verified: all 5 nav labels present, `<html class="dark …">`, Inter + JetBrains Mono fonts referenced; served CSS contains `--color-background` / `#0a0a0f`.

### Completion Notes List

- **Theme (Task 1):** Rewrote `globals.css` using Tailwind v4 `@theme` with the exact §12 hex tokens (background/surface/border/accent/gains/losses/warning/text-primary/text-secondary) exposed as utilities (`bg-background`, `text-text-primary`, etc.). App is dark-always (`<html class="dark">`); no light variant. Swapped Geist → Inter (sans) + JetBrains Mono (mono) via `next/font/google` (self-hosted at build — offline-safe for the Mini). Title set to "Argus".
- **Shell (Task 2):** Added `cn()` (clsx + tailwind-merge — shadcn convention) and a reusable `Skeleton`. Built `Sidebar`, `TopBar`, `RightPanel`, `BottomStrip`, `BottomNav` under `components/shell/`. Nav has one source of truth (`navItems.tsx`) shared by Sidebar + BottomNav, with inline-SVG icons (no icon-library dependency, fully offline). Active-route highlight via `usePathname()` + an `isActive()` helper (exact match for `/`, prefix match for sub-routes); Sidebar/BottomNav are client components.
- **Routes (Task 3):** Created the `(dashboard)` route group with a shell-composing `layout.tsx` (Sidebar at `lg:`, RightPanel at `xl:`, BottomStrip at `lg:`, BottomNav `lg:hidden`; main always visible). Stub pages for Home (Morning Briefing placeholder + Top Movers / Calendar cards), Portfolio, Intelligence, Agents, Profile — each a titled surface card with a `Skeleton` demo. Added a route-level `loading.tsx` to explicitly satisfy AC #4. Removed the create-next-app splash `page.tsx` and the demo `public/*.svg` assets.
- **PWA (Task 4):** `manifest.ts` (Next metadata route) — name/short_name, `standalone`, `start_url` `/`, theme/background `#0A0A0F`, plus a placeholder `public/icon.svg`. Installable; service worker + Web Push remain deferred to Epic 8 per scope.
- **Responsive-visibility decision:** Each shell component owns its own responsive show/hide classes (e.g. Sidebar `hidden lg:flex`) rather than the layout wrapping them in visibility divs — wrappers would disrupt the flex sizing of fixed-width chrome. The layout still composes by ordering.
- **Dependencies:** Added `clsx` + `tailwind-merge` (both named explicitly in Task 2). No other new deps; icons are inline SVG.
- **Out of scope (left untouched):** `lib/apiClient.ts` / `lib/wsClient.ts` not consumed (shell is static); `hooks/`, `stores/`, `features/` keep their `.gitkeep`. No frontend test harness added (deferred); verification is build + curl per the story's testing standard.

### File List

**New:**
- `frontend/src/app/(dashboard)/layout.tsx`
- `frontend/src/app/(dashboard)/loading.tsx`
- `frontend/src/app/(dashboard)/page.tsx`
- `frontend/src/app/(dashboard)/portfolio/page.tsx`
- `frontend/src/app/(dashboard)/intelligence/page.tsx`
- `frontend/src/app/(dashboard)/agents/page.tsx`
- `frontend/src/app/(dashboard)/profile/page.tsx`
- `frontend/src/app/manifest.ts`
- `frontend/src/components/ui/Skeleton.tsx`
- `frontend/src/components/shell/navItems.tsx`
- `frontend/src/components/shell/Sidebar.tsx`
- `frontend/src/components/shell/TopBar.tsx`
- `frontend/src/components/shell/RightPanel.tsx`
- `frontend/src/components/shell/BottomStrip.tsx`
- `frontend/src/components/shell/BottomNav.tsx`
- `frontend/src/lib/utils.ts`
- `frontend/public/icon.svg`
- `frontend/public/icon-192.png` *(review: iOS PWA icon)*
- `frontend/public/icon-512.png` *(review: iOS PWA icon + maskable)*
- `frontend/src/app/apple-icon.png` *(review: apple-touch-icon)*

**Modified:**
- `frontend/src/app/layout.tsx`
- `frontend/src/app/globals.css`
- `frontend/src/app/manifest.ts` *(review: PNG icons)*
- `frontend/src/app/(dashboard)/layout.tsx` *(review: h-dvh)*
- `frontend/src/app/(dashboard)/loading.tsx` *(review: role=status)*
- `frontend/src/components/shell/BottomNav.tsx` *(review: nowrap + safe-area)*
- `frontend/src/components/ui/Skeleton.tsx` *(review: aria-hidden)*
- `frontend/package.json`
- `frontend/package-lock.json`

**Deleted:**
- `frontend/src/app/page.tsx`
- `frontend/public/file.svg`
- `frontend/public/globe.svg`
- `frontend/public/next.svg`
- `frontend/public/vercel.svg`
- `frontend/public/window.svg`

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-19 | Implemented dark-theme dashboard shell: §12 theme tokens + Inter/JetBrains Mono, responsive shell (sidebar/top bar/right panel/bottom strip/mobile bottom nav), `(dashboard)` route group with 5 stub pages, reusable Skeleton + route loading state, and an installable PWA manifest. Build + dev-server curl checks pass. Status → review. |
| 2026-06-19 | Code review (3 adversarial layers): no acceptance violations. 2 decisions resolved (breakpoints kept as-is per §12 hint; generated iOS PNG icons), 9 patches applied (reduced-motion, Skeleton/loading a11y, mobile-nav label nowrap, h-dvh, safe-area inset, theme-color/color-scheme viewport, twMerge custom-token config, trailing newlines, tabular-nums cleanup), 8 dismissed. Rebuild clean; head tags + icons verified. Status → done. |
