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
  15 min and slides with each request. **Every** non-public request goes through `SessionAuthFilter`,
  which fails closed.
- **Public (no-session) paths:**
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
| morning briefings (V71) | **trade journal / decisions** (`trade_decisions`) |
| investor profile + onboarding (V72) | paper trades, simulated trades, graduation, calibration, adaptive tuning, learned rules |
| live-portfolio WebSocket (`publishToUser`) | news, social, SEC, filings, fundamentals, candles, FX, calendar, strategies, deep analysis |
| push for briefing + import results (`sendToUser`) | **watchlist**, **notification preferences**, deferred notifications |
| Ask-AI portfolio context (built from that user's live snapshot; no chat history stored) | `app_settings`: **session timeout** and **Demo Mode** |
| | cost / budget events (one shared Haiku budget) |

`push_subscriptions.user_id` is a plain column, not `@TenantId`. Pushes sent with `sendToAll` reach
**everyone's** devices (see §5).

## 4. Admin

- Admin UI: the **People on Argus** section on `/profile` (`features/auth/AdminUserStats.tsx`). It
  hides itself when the API returns 403. There is no separate admin page.
- `GET /api/admin/users` returns usage stats: joined, last login, login count, active days, active
  minutes (from `user_activity_day`, touched at most once a minute). **It returns no financial data**.
- The other admin endpoints are `GET/POST /api/admin/invites` and `POST /api/admin/invites/send`, all
  behind `CurrentUserService.requireAdmin`.
- There are **no** endpoints to un-invite someone, remove a user, or change who is admin. Today these
  are done with SQL on `invited_email` / `app_user`.

## 5. Known gaps (security-relevant now that the app is public via Funnel)

These were inherited from the single-user design and found during the October 2026 review. They are
not fixed yet.

1. **Session list and revoke are not scoped to a user.**
   - `GET /api/auth/sessions` lists *every* user's sessions, and `DELETE /api/auth/sessions/{handle}`
     revokes *any* of them.
   - Cause: `SessionStore.list` / `revokeByHandle` scan all `argus:session:*` keys, and
     `AuthController` does no owner check.
2. **Global settings can be changed by any user.**
   - The session timeout (`PUT /api/settings/session-timeout`) changes the TTL of everyone's live
     sessions.
   - Demo Mode (`/api/settings/demo-mode`) flips for everyone.
   - Neither is admin-gated.
3. **Ops actions are not admin-gated:**
   - `POST /api/ops/backup/trigger`
   - cleanup `/preview` and `/run`
   - logic-review `/run`
   - tuning `/recompute`
   - graduation `/resume`
4. **Broadcast pushes go to every user's devices.** `NotificationService` (`sendToAll`), the cleanup
   scheduler, the weekly digest, breaking-news alerts and `PushController /test` all broadcast. A
   ticker-specific alert driven by one person's holdings can therefore hint at what they hold.
5. **Shared journal, watchlist and notification preferences.** One user's Take/Decline decision
   appears in everyone's Trade Journal.
6. **Background jobs see config defaults, not the person.** `CanadianContextService` falls back to
   `argus.investor.*` when there is no user on the thread.
7. **The admin email is hardcoded in migrations.** V68 seeds it and V69–V72 backfill to it. V70 and
   V71 fail on a fresh database that already has portfolio or briefing rows and lacks that admin user.
8. **A leftover pre-Google session** (with no `userId`) makes `/api/auth/status` report
   `authenticated=true` with `user=null`.

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
