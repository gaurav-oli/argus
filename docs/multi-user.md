# Argus — Multi-User (Invite-Only) Access

Argus started as a single-user app (PIN + passkey login, tailnet-only). Since October 2026 it is
**invite-only multi-user**: friends sign in with Google, but only if the admin has put their email on
the allowlist. Each person's **portfolio is private**; the market intelligence the agents produce is
**shared**.

Commits: `ba1b583` (Phase 1: Google Sign-In, allowlist, admin stats) · `3487e04` (Phase 2: per-user
portfolio isolation) · `4145892` (in-app invites) · `5e70054` (onboarding, per-user investor profile) ·
`23dd9de` (PIN/WebAuthn removed) · `4b34c28` + `4d5fb07` (invite emails via Gmail SMTP).
Migrations **V68–V74**.

## 1. How someone gets in

```
Admin: Profile → "People on Argus" → Invite a friend (email)
        │  POST /api/admin/invites/send
        ▼
invited_email row (+ unique link token) ──► Gmail SMTP email: ${ARGUS_APP_URL}/?invite=<token>
        │
Friend opens link ──► POST /api/invite/open?token=…   (sets opened_at; no session needed)
        │
"Sign in with Google" ──► GET /api/auth/google/login  (302 to Google, ARGUS_OAUTH_STATE cookie)
        │
Google ──► GET /api/login/oauth2/code/google
        │   verify state → exchange code → verify ID token (Google JWKS, aud = client id)
        │   → require email_verified → lowercase email
        ├─ known google_sub          → refresh name/picture, log in
        ├─ email on invited_email     → create app_user (admin iff == ARGUS_ADMIN_EMAIL), log in
        └─ not invited                → 302 /?auth=not_invited  (no user row created)
        ▼
Redis session argus:session:{id} (userId on the hash) + ARGUS_SESSION cookie
        ▼
First login only: 3 skippable onboarding questions (style, horizon, goal) → investor_profile
```

- The OAuth flow is hand-rolled (`security/GoogleOAuthService`, `GoogleAuthController`), not Spring
  Security OAuth2.
- **Session cookie** `ARGUS_SESSION`: HttpOnly, Secure, SameSite=Strict. The idle timeout defaults to
  15 min and slides with each request. **Every** non-public `/api/**` request goes through
  `SessionAuthFilter`, which fails closed. **WebSocket `/ws` is not covered by that filter** — see
  §5.0 (critical gap while Funnel exposes `/ws`).
- **Public (no-session) REST paths:**
  - `GET /api/auth/status`
  - `GET /api/auth/google/login`
  - `GET /api/login/oauth2/code/google`
  - `POST /api/invite/open`
- The invite funnel shown to the admin goes **not sent → sent → opened → joined**. "Joined" means an
  `app_user` row exists for that email.
- A failed email send still leaves the address on the allowlist, so the friend can still sign in.

## 2. Configuration

| Env var | Purpose |
|---|---|
| `ARGUS_GOOGLE_OAUTH_CLIENT_ID` / `_CLIENT_SECRET` | Google Cloud OAuth client. Its consent screen stays in **Testing** mode, so each invitee must also be added as a Test User in the Google console, or Google itself blocks them. |
| `ARGUS_GOOGLE_OAUTH_REDIRECT_URI` | Must exactly match the URI registered in Google: `https://<host>.<tailnet>.ts.net/api/login/oauth2/code/google` |
| `ARGUS_ADMIN_EMAIL` | Promoted to admin **when that account is first created**, and never re-evaluated after that. |
| `ARGUS_APP_URL` | Base URL used in invite links, i.e. the public Funnel host. |
| `ARGUS_GMAIL_ADDRESS` / `ARGUS_GMAIL_APP_PASSWORD` | Gmail SMTP sender (`smtp.gmail.com:587`, STARTTLS). This needs a Google **App Password**, not your account password. |
| `ARGUS_WEB_ALLOWED_ORIGINS` | Must include the Funnel origin. It is used by CORS and the WebSocket origin check. |

If the client id or secret is blank, `/api/auth/google/login` returns 503 and nobody can sign in. If
the Gmail settings are blank, invites can still be added but **Send** returns 503.

## 3. What is private vs shared

Isolation uses Hibernate `@TenantId` on `user_id`:

- `PortfolioTenantResolver` reads `CurrentUserContext`. With no user, it resolves to id `0`, so queries
  return no rows (fails closed).
- Scheduled jobs that need per-user data loop over users with `CurrentUserContext.runAs(...)`. These
  are the briefing, health score, value history, live portfolio and statement import.
- Price feeds and Agent 3 collect tickers across all users through native queries
  (`PositionRepository.allTickersAcrossAllUsers()`). Those queries return **symbols only**, never
  quantities or values.

| Private (per user) | Shared (global) |
|---|---|
| positions, lots, position audit, cash, account meta, corporate actions, imports, value history, health score (V70) | recommendations, signals, debates, persona verdicts |
| morning briefings (V71) | the paper Investor's (AGENT) decisions in `trade_decisions` |
| investor profile + onboarding (V72) | paper trades, simulated trades, graduation, calibration, adaptive tuning, learned rules |
| live-portfolio WebSocket (`publishToUser`) | news, social, SEC, filings, fundamentals, candles, FX, calendar, strategies, deep analysis |
| push for briefing + import results (`sendToUser`) | discovered watchlist entries, deferred notifications |
| your own Take/Decline decisions in the Trade Journal (V91, `user_id`) | agent coverage = holdings ∪ everyone's watchlist picks |
| your manual watchlist picks (V91, `user_id`) | |
| notification preferences (V91, `user_notification_prefs`) | |
| Ask-AI portfolio context (built from that user's live snapshot; no chat history stored) | `app_settings`: **session timeout** and **Demo Mode** |
| | cost / budget events (one shared Haiku budget) |

`push_subscriptions.user_id` is a plain column, not `@TenantId`. Pushes sent with `sendToAll` reach
everyone's devices. Since S-C1 every producer passes a recipient filter, so each device only gets a push its
owner's preferences allow (see §5.5).

## 4. Admin

- Admin UI: the **People on Argus** section on `/profile` (`features/auth/AdminUserStats.tsx`). It
  hides itself when the API returns 403. There is no separate admin page.
- `GET /api/admin/users` returns usage stats: joined, last login, login count, active days, active
  minutes (from `user_activity_day`, touched at most once a minute). **It returns no financial data**.
- The other admin endpoints are `GET/POST /api/admin/invites` and `POST /api/admin/invites/send`, all
  behind `CurrentUserService.requireAdmin`.
- **Offboarding** (`1aa3f3c`, story S-C2). These are buttons on each person's row in People on Argus:
  - `POST /api/admin/users/revoke` locks someone out but keeps their data. It sets `revoked_at` and ends every
    session they have (`SessionStore.revokeAllForUser`). A revoked person who tries to sign in gets the same
    "not invited" answer as a stranger. Any session that outlived the revoke resolves to no user.
  - `POST /api/admin/users/restore` gives them access back with their data intact.
  - `POST /api/admin/users/delete` permanently removes the account, every per-user table
    (`UserDeletionService.USER_TABLES`, guarded by a test) and their invite. It ends their sessions first.
  - `POST /api/admin/invites/remove` withdraws an unused invite. It refuses (409) once they've joined.
  - An admin can't be revoked or deleted through these endpoints.
- There is still no endpoint to change who is admin. That is a one-line SQL update on `app_user.is_admin`.

## 5. Known gaps (security-relevant now that the app is public via Funnel)

Inherited from the single-user / tailnet-only design; still open as of the October 2026 review.
Work queue with story statuses (S-A1…): **[`docs/platform-improvement-plan.md`](platform-improvement-plan.md)**.
Strike items here when fixed and set the matching story to `done` in that plan.

### 5.0 Critical — WebSocket portfolio queue (C2) — **fixed (S-A1, 2026-10-09)**

`/ws` still sits outside `SessionAuthFilter` (`/api/*` only), but:

- `SessionAuthHandshakeInterceptor` rejects the upgrade unless `ARGUS_SESSION` has a `userId`.
- `StompDestinationGuard` rejects client SUBSCRIBE/SEND to raw `/queue/**` (including
  `/queue/portfolio-user{id}`). Clients use `/user/queue/portfolio` only.

### 5.1 Critical — Session list / revoke not scoped (C1) — **fixed (S-A2, 2026-10-09)**

`SessionStore.list(currentId, ownerUserId)` and `revokeByHandle(handle, ownerUserId)` only touch
sessions owned by the caller. Foreign handles return 404 (same as unknown).

### 5.2 High — Global settings writable by any user (H1) — **fixed (S-A3, 2026-10-09)**

`PUT` session-timeout and Demo Mode require admin. GETs remain available to any signed-in user.

### 5.3 Critical — Ops mutators not admin-gated (C3) — **fixed (S-A3, 2026-10-09)**

These mutators now call `requireAdmin`:

- `POST /api/ops/backup/trigger`
- cleanup `/preview` and `/run`
- logic-review `/run`
- tuning `/recompute`
- graduation `/resume`

Ops GETs (summary, backup status, last cleanup, etc.) stay session-gated for the dashboard.

### 5.4 High — Broadcast pushes can leak holdings signals (H2) — **fixed (S-A4)**

Ticker-linked `NotificationService` alerts and holdings-impact breaking news go only to
`PositionRepository.userIdsHoldingTicker` via `sendToUser`. `/api/push/test` pings the caller only.
True market-wide paths (macro/crisis breaking, weekly digest, monthly cleanup) still use `sendToAll`.

### 5.5 High — Shared journal, watchlist, notification preferences (H3) — **fixed (S-C1, 2026-10-10)**

V91 makes all three per-user. Existing data went to the admin.

- **Trade Journal:**
  - A person's Take/Decline is stored with their `user_id`. They see the shared Investor (AGENT) decisions plus only their own; someone else's decision reads as not found.
  - A human decision no longer rewrites the shared recommendation's status.
  - It also no longer blocks the Investor from recording its own decision.
- **Watchlist:**
  - Manual picks have a `user_id`. Each person sees their own picks plus the shared discoveries, and can remove only their own.
  - Re-running discovery or dropping a discovered name needs admin.
  - Agent coverage stays the union of everyone's picks, because the research is shared.
- **Notification preferences:**
  - Stored one row per person in `user_notification_prefs`. The old singleton seeded everyone who existed at upgrade.
  - Each push checks the recipient's own preferences. Broadcasts (`PushService.sendToAll` with a recipient filter) skip the devices of people who turned that category off, muted the ticker, or are in quiet hours.

### 5.6 Medium — Background jobs see config defaults (M7)

`CanadianContextService` falls back to `argus.investor.*` when there is no user on the thread.
Jobs that need a person should always `CurrentUserContext.runAs(...)`.

### 5.7 Low — Admin email hardcoded in migrations (L2)

V68 seeds it and V69–V72 backfill to it. V70 and V71 fail on a fresh database that already has
portfolio or briefing rows and lacks that admin user. Prefer env/Flyway placeholders; avoid
committing a personal address.

### 5.8 Medium — Userless sessions still authenticate (M2) — **fixed (S-A6)**

`SessionStore.validate` requires a `userId` field. `/api/auth/status` reports authenticated only
when a non-revoked `AppUser` resolves — never `authenticated=true` with `user=null`.

### 5.9 High — Cost Governor hole on Haiku fallbacks (H5) — **fixed (S-A5)**

Every Haiku path in `DefaultModelGateway` goes through `paidFallback`, which checks
`CostGovernor.allowPaidCall()`. At ≥95% budget, escalate uses local BIG; generateBig fallbacks
(timeout / blank / primary failure) refuse paid calls instead of silently billing.

### 5.10 Medium — No per-user AI / import quotas (M1)

Ask-AI, debate, research, deep-analysis, and LLM import paths are session-gated only. Shared
monthly Haiku budget. `escalate()` also bypasses the BIG-tier concurrency semaphore.

### 5.11 Medium — Push unsubscribe IDOR + weak PDF check (M4) — **fixed (S-A6)**

- Push unsubscribe deletes by `(endpoint, userId)` for the signed-in caller only.
- Statement upload requires `%PDF` magic bytes (content-type / `.pdf` filename alone rejected).

### 5.12 Product (optional) — Take/Decline UI orphaned (C4 → story S-D1)

`RecommendationCards` (Take/Decline, debate, personas) is unmounted after the Intelligence
rebuild. Owner vision prioritizes the **Investor paper → learn** loop; human Agree/Disagree is
optional later (**S-D1** in `platform-improvement-plan.md`). See also `design-terminal-noir.md`.

## 6. Leftovers from the single-user era

- **Removed** (V73, `23dd9de`): PIN, WebAuthn/passkeys, failed-attempt lockout.
- **Still present:**
  - **Panic mode.** It is per device (localStorage) and logs out.
  - **Tap-to-reveal** `Sensitive` masking on money and score values. Tickers are no longer masked
    (`528b07d`).
  - **Demo Mode.** It is global; see §5.2.
- **Remote session kill** (Story 2.7) still exists, but see §5.1.
- **Stale comments** still mention PIN, Resend or "single-user scale": `SessionStore`,
  `security/package-info.java`, `PanicProvider.tsx`, `AdminController`, `apiClient.ts`.
