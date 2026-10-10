# Argus — Platform Review (October 2026)

Durable pickup document for the full-platform review (architecture, security, product/UX).
Use this as the backlog source when hardening Funnel multi-user and restoring the discipline loop.

**Status:** open — findings verified against code; fixes not yet applied.  
**Companion canvas:** Cursor canvases `argus-platform-review.canvas.tsx` (optional UI).  
**Related docs:** [`multi-user.md`](multi-user.md) §5 (security gaps), [`deploy-runbook.md`](deploy-runbook.md),
[`design-terminal-noir.md`](design-terminal-noir.md) (RecommendationCards orphan).

---

## Verdict

Argus is a mature modular monolith with a strong judgment machine (deterministic probability
scoring, graduation, calibration, Model Gateway, Cost Governor, AgentRuntime). The product thesis
(“decision-support & discipline, not alpha”) is real in the backend.

Two structural gaps dominate after invite-only Funnel:

1. **Authorization / tenancy leftovers** from single-user / tailnet-only design — now high impact
   because Funnel publishes `/`, `/api`, and `/ws` to the public internet.
2. **Product drift** — Intelligence rebuild left human Take/Decline (and debate/personas) as dead
   code while chrome still reads like an alpha tip sheet.

Auth foundations that *are* solid: Google ID-token verify + invite allowlist, session cookie flags
(HttpOnly / Secure / SameSite=Strict), fail-closed `SessionAuthFilter` for `/api/**`, portfolio
`@TenantId` fail-closed to `user_id=0`.

---

## Findings (by severity)

Mark items done in place when fixed; keep this file as the checklist.

### Critical

| ID | Finding | Evidence | Impact | Suggested fix |
|---|---|---|---|---|
| C1 | Session list/revoke not scoped to owner | `SessionStore.list` / `revokeByHandle`; `AuthController` | Any invitee lists/kills everyone’s sessions | Filter by `userId`; owner-only revoke |
| C2 | Unauthenticated STOMP can subscribe to live portfolio queues | `SessionAuthFilter` is `/api/*` only; handshake allows no Principal; broker `/queue` with no `ChannelInterceptor`; `convertAndSendToUser` → `/queue/portfolio-user{id}` | Guess sequential ids over Funnel `/ws` → holdings/P&L without login | Require session+userId on handshake; deny client SUBSCRIBE to raw `/queue/**` |
| C3 | Ops mutators not admin-gated | `POST /api/ops/backup/trigger`, cleanup `/run`, logic-review `/run`, tuning `/recompute`, graduation `/resume` | Any invitee operates the Mini / Agent 5 | `requireAdmin` on mutators |
| C4 | Human Take/Decline UI is dead code | `RecommendationCards` unmounted; `decideRecommendation` only used there; design doc notes orphan | Discipline loop (decide → journal → regret) unreachable | Remount on Intelligence `TickerDetail` / Today cards |

### High

| ID | Finding | Evidence | Impact | Suggested fix |
|---|---|---|---|---|
| H1 | Global settings writable by any user | session-timeout, Demo Mode | One friend changes TTL / Demo for all | Admin-only writes |
| H2 | Broadcast pushes can leak holdings hints | `NotificationService.sendToAll`; digest/breaking/cleanup/test | Side-channel on private holdings | `sendToUser` for portfolio-derived alerts |
| H3 | Shared journal / watchlist / notif prefs | `trade_decisions`, watchlist CRUD, prefs singleton | Privacy + integrity break | Per-user scope (or admin-managed watchlist) |
| H4 | README vs chrome positioning | layout/manifest “AI-powered investment intelligence”; no in-UI non-advice copy | Looks like alpha tips | Align metadata + persistent decision-support line |
| H5 | Haiku fallbacks bypass Cost Governor | `escalate()` checks `allowPaidCall()`; `generateBig()` fallbacks do not | 95% auto-switch NFR incomplete | Gate every Haiku path |

### Medium

| ID | Finding | Evidence | Impact | Suggested fix |
|---|---|---|---|---|
| M1 | No per-user AI/import quotas; `escalate()` skips BIG semaphore | chat/debate/research/deep/import | Budget + Gemma saturation | Quotas + rate limits |
| M2 | Userless sessions still authenticate | `validate()` ignores missing `userId` | Shared endpoints with `user=null` | Reject sessions without `userId` |
| M3 | Agent Streams spine covers only 3 agents | `NewsSentimentAgent`, `RecommendationTrigger`, `DemoAgent` | Most fleet is cron-only, no PEL/DLQ | Document hybrid or migrate wakes |
| M4 | Weak PDF type check; push unsubscribe IDOR | content-type OR `.pdf`; unsubscribe by endpoint | DoS / Haiku burn; unsub others | `%PDF` magic; scope by `user_id` |
| M5 | No in-app uninvite / user disable | admin invites only | SQL for offboarding | Admin disable + revoke-all |
| M6 | No `error.tsx`; silent fetch failures | frontend | Blank app / stale numbers | Error boundary + visible refresh failures |
| M7 | Jobs fall back to `argus.investor.*` defaults | `CanadianContextService` without `runAs` | Wrong persona context | Always `runAs` target user |

### Low

| ID | Finding | Evidence | Impact | Suggested fix |
|---|---|---|---|---|
| L1 | Black Swan stub; `EXPECTED_AGENTS=10` vs ~15 fleet | `isBlackSwanActive()` always false | Cap inert; coverage skew | Wire detector; align denominator |
| L2 | Admin email hardcoded in Flyway V68–V72 | migrations seed/backfill | Fresh-DB friction; identity in git | Env/placeholder seed; scrub if needed |
| L3 | Research markdown `href` pass-through | `ResearchJobDetail` | Theoretical `javascript:` links | Allowlist `http(s):` |
| L4 | CSRF absent (mitigated by SameSite=Strict) | no Spring CSRF | Residual if XSS / Secure off | Defense in depth if Funnel grows |

---

## Recommended sequencing

### P0 — before more invites

1. C2 — lock WebSocket (session required + block raw `/queue/**` subscribe)
2. C1 — scope sessions list/revoke
3. C3 + H1 — admin-gate ops + settings + Demo Mode
4. H2 — push routing
5. C4 + H4 — remount Take/Decline + framing copy

### P1

- H3 / H5 / M1 / M2 / M5 / M6 — journal tenancy, Haiku gate, quotas, userless sessions, uninvite, error UI
- Calibration chip beside Intelligence calls (Brier / sample-size)

### P2

- M3 / L1 — agent pattern clarity, Black Swan, Mini BIG admission + cron stagger
- Import audit trail, DLQ on Agents page, reboot automation, a11y polish

---

## Architecture notes (code vs June 2026 doc)

| June 2026 architecture intent | October 2026 reality |
|---|---|
| Tailscale-only, never Funnel | **Superseded** — Funnel public HTTPS (`deploy-runbook.md`) |
| PIN + WebAuthn | **Removed** (V73); Google invite allowlist |
| MongoDB + Postgres | **Postgres + Redis only** (Mongo dropped) |
| Redis Streams as agent spine | **Partial** — excellent `AgentRuntime`; only 3 `Agent` beans; most agents `@Scheduled` |
| Single user | Invite-only multi-user; portfolio tenancy solid; shared intel/journal incomplete |

Living amendments live at the top of
`_bmad-output/planning-artifacts/architecture.md`. Historical body stays as the design record.

---

## Product / UX notes

| Surface | State |
|---|---|
| Home / Portfolio / Agents / Profile | Strong situational + ops surfaces |
| Intelligence | Best research UX; **lost human decision loop** |
| `RecommendationCards` | Dead code (also noted in `design-terminal-noir.md`) |
| Calibration / paper book | Honest but secondary (Agents page); paper Investor auto-trades |
| PWA SW | Push-only (`public/sw.js`); not offline shell cache |
| Disclaimers | README disciplined; in-app metadata/marketing not |

---

## What is done well (keep)

- `ProbabilityScoringEngine` — no LLM probabilities; neutral prior + auditable contributions
- Graduation + adaptive tuning (Brier-gated) + paper investor
- Model Gateway timeouts / BIG semaphore / Haiku escalate path (with H5 hole on fallbacks)
- AgentRuntime PEL reclaim, dedupe, DLQ
- Portfolio `@TenantId` + isolation integration tests
- Ops: freshness, backup status, NORMAL/DEGRADED, cost bands
- Invite open always 204 (no token oracle); Google state CSRF cookie

---

## Doc sync checklist (this review)

| Doc | Action taken |
|---|---|
| This file | Created as canonical findings backlog |
| `docs/multi-user.md` | §5 expanded to match code (incl. WS portfolio queues) |
| `_bmad-output/.../architecture.md` | Living amendments header for Funnel / auth / agents |
| `README.md` | Link to this review |
| `docs/design-terminal-noir.md` | Pointer to C4 / remount priority |

When a finding is fixed, tick it here **and** remove or strike the matching bullet in
`multi-user.md` §5 so the two stay aligned.
