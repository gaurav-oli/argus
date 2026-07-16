# Mac Mini — Pending Hardware Validation

Things that **can only be run/verified on the Mac Mini M3 (28GB)** and were
therefore deferred during laptop development. Work through this checklist when
you're on the Mini. Each item links back to its story.

> Why deferred: the dev laptop is a MacBook Pro M5 / 16GB — it cannot load the
> 26B model and is not the deployment target. Everything below was either built
> + verified *structurally* on the laptop, or explicitly gated on the hardware.

## 1. Story 1.3 — RAM + latency validation spike (GAP-1/2)  ✅ DONE 2026-06-21
Status: **done**. Validated on the Mini (Gemma 4 26B MoE, full Docker stack running).
- [x] Installed Ollama natively; `ollama pull gemma4:26b` (Gemma 4, the latest family; supersedes the provisional `gemma3:27b`).
- [x] Loaded the 26B MoE under worst case (Postgres + Redis + JVM + frontend + a generation request).
- [x] **Recorded RAM:** model ≈ **17GB resident** (93% GPU/Metal, 7% CPU). With the model loaded + full stack + macOS, PhysMem ≈ **23GB used, ~0 free, ~5.8GB swap** → the 26B resident **overflows the 28GB ceiling into swap**. At rest (model unloaded) PhysMem ≈ **6GB used, 18GB free** → ample headroom.
- [x] **Measured latency:** prompt-eval **0.85s**, eval rate **~22 tok/s**, **cold-load ~28s**. Warm first-token **<1s** (passes ≤15s for normal answers); cold-load fails ≤15s.
- [x] **Decision (RAM + cold-load both stressed → policy revised + re-tested):** keep `gemma4:26b` for quality, but **do NOT pin it resident**. Set `ARGUS_MODEL_KEEP_ALIVE=5m` (unload-when-idle) so the model frees ~17GB when idle and reloads on demand; the one-time ~28s cold-load is accepted (Ask-AI should show a "warming up" state — Epic 7). Re-tested: at-rest RAM confirmed healthy (18GB free). Lighter fallback if needed: **Gemma 4 12B Unified**.
- [x] Set the validated values: `.env` on the Mini + committed defaults (`application-prod.yml`, `docker-compose.yml`, `.env.example`) → `ARGUS_BIG_MODEL=gemma4:26b`, `ARGUS_MODEL_KEEP_ALIVE=5m`.
- [x] Updated sprint-status `1-3-…` → done.
[Source: epics.md#Story 1.3; architecture.md#GAP-1/2]

> **REVISION 2026-06-24 (during Epic 7 live Ask-AI validation): Mini runtime switched to `gemma4:12b`.**
> The first *real* backend→Ollama chat calls exposed that the 26B is not viable for interactive
> chat **under the full prod Docker stack**: with the 4 containers (Docker VM) + the 17GB model
> loaded, the box runs at ~0 mem free / swap saturated (~8.8GB), and every request stalls **20–60s
> paging the model back in before generation** — a recurring penalty, not the one-time cold-load §1
> accepted. Measured 40-token answers took 23s and 65s wall-clock with only ~1.7s of compute.
> `gemma4:12b` (Gemma 4 12B dense "Unified", 7.6GB) fixes it: **56% mem free, 100% GPU, no stalls,
> predictable**. `.env` `ARGUS_BIG_MODEL=gemma4:12b`; 26B stays a quality option for when the box has
> dedicated RAM (it's the deploy box — close IntelliJ/dev apps). Committed prod defaults still say 26b.

> **CORRECTION 2026-06-25 — the dominant latency cause is a BROKEN `gemma4` MODEL BUILD, not RAM/streaming.**
> Deeper investigation during the §5 live smoke test found that *every* chat call generates **~400–640
> tokens regardless of answer length** — a 28-word answer reported `eval_count=461`, with ~420 of those
> tokens decoding to the **empty string** (invisible special/pad tokens). At ~12 tok/s that junk tail
> *is* the 35–55s latency. Root cause: the custom-imported `gemma4` tags (both 12B and 26B — multimodal
> builds with a clip projector) ship a stripped `TEMPLATE {{ .Prompt }}` and **no `PARAMETER stop`**;
> `ollama show` confirms it. Re-templating a throwaway copy with a proper Gemma template + `stop
> "<end_of_turn>"` did **not** stop the junk generation, so the GGUF/tokenizer itself is bad. Therefore:
> **token-streaming would NOT fix this** (the model still generates the junk before any stop), and it's
> not primarily a RAM problem (12B has 56% mem free and still does it). The real fix is to **replace the
> model** with a properly-packaged one (e.g. official `gemma3:12b`/`gemma3:27b`), deferred per the user
> (2026-06-25): some local latency is acceptable since **Anthropic Haiku handles heavy lifting** (§7,
> validated, ~5.6s) and local Gemma is only for lighter grounded Q&A. Logged as a known issue.

## 2. Story 1.8 — deploy + Tailscale, real run  (Mini run done 2026-06-21; iPhone/WS check open)
The artifacts (Dockerfiles, compose `deploy` profile, runbook) are built + verified
on the laptop. Real Mini run executed 2026-06-21. Full procedure: **`docs/deploy-runbook.md`**.
- [x] On the Mini: `git pull` → `docker compose --profile deploy up -d --build` — all 4 containers **healthy**.
- [x] **`prod` profile** boots end-to-end: Flyway migrated, backend on profile `prod` (`/api/system-info` → `"profile":"prod"`), connected to Postgres + Redis by compose service name. _(Backend→Ollama is config-wired via `host.docker.internal`; an actual model call through the backend awaits the Ask-AI endpoint in Epic 7 — Ollama itself validated directly in §1.)_
- [x] `tailscale up` + MagicDNS (`leannas-mac-mini.taila43287.ts.net`); `tailscale cert` issued (after enabling HTTPS Certificates in the admin console); `tailscale serve` HTTPS, **tailnet-only**.
- [x] **`tailscale serve` syntax** for this version: root has **no** `--set-path` (`tailscale serve --bg --yes --https=443 <target>`); `/api` + `/ws` use `--set-path`. `/api` verified: `curl https://…ts.net/api/system-info` → prod JSON (no prefix-strip issue).
- [x] Opened `https://leannas-mac-mini.taila43287.ts.net` **on the iPhone** (Tailscale connected, same tailnet) → **dashboard shell loads** (2026-06-21). Root over HTTPS also `curl -I` → `HTTP/2 200`.
- [x] REST round-trip through the deployed stack verified (curl `/api/system-info` → prod JSON, on Mini + over Tailscale). _(Live `/ws` over Tailscale: serve mount configured + WS handler verified at the backend in Story 1.6; a visible live-data check awaits the first real live feature — the shell is a skeleton.)_
- [x] **No public exposure:** access via `tailscale serve` (never `funnel`); all published container ports bind `127.0.0.1` only (confirmed in `docker compose ps`).
- [x] Frontend image built with single-origin args (`NEXT_PUBLIC_API_BASE_URL=` empty → same-origin `/api`; `NEXT_PUBLIC_WS_URL=wss://leannas-mac-mini.taila43287.ts.net/ws`).
[Source: 1-8-tailscale-access-deploy-to-mini-runbook.md AC#6]

## 3. Epic 8 — Push (FR-17) + Briefing (FR-16) + Alert discipline (FR-18/19/20)  ⏳ TODO on the Mini
Built + statically verified on the laptop (backend `mvn compile` **and** `test-compile` green against the
real `nl.martijndwars:web-push` API; the alert-discipline pipeline 8.2/8.3/8.4 has **passing laptop unit
tests** — `NotificationServiceTest`; frontend `tsc --noEmit` + `eslint` green). Everything below needs a
real browser over HTTPS, a real VAPID private key, network egress to the push services, the live model,
and a running Redis — none fully exercisable on the dev MacBook.

**Prerequisite (carried over):**
- [ ] Tailscale HTTPS origin valid so the service worker + Web Push can register on the iPhone (HTTPS set up in §2).

**Dependency resolution (do first — could not be checked offline):**
- [ ] `docker compose --profile deploy up -d --build` (or `mvn -DskipTests package`) resolves
      `nl.martijndwars:web-push:5.1.1` + `bcpkix-jdk18on:1.81`. The pom **excludes** web-push's legacy
      `bcprov/bcpkix-jdk15on` in favour of the `bcprov-jdk18on:1.81` already on the classpath (Argon2) —
      confirm there's no duplicate-BouncyCastle classpath conflict and the context starts.

**VAPID keys:**
- [ ] Set `ARGUS_PUSH_VAPID_PRIVATE` in the Mini `.env` (the matching private key for the public key in
      `.env.example`). With it blank, `PushService.isConfigured()` is false and `sendToAll` no-ops by design.

**Web Push end-to-end (FR-17):**
- [ ] iPhone: install Argus to the Home Screen (manifest already shipped), open it, Profile → Notifications →
      **Enable notifications** → permission granted → `POST /api/push/subscribe` stores a row in `push_subscriptions`.
- [ ] Service worker registers (`/sw.js`, via `instrumentation-client.ts`); `navigator.serviceWorker.ready` resolves.
- [ ] Trigger a broadcast and confirm the OS notification appears and **clicking it opens/focuses the app**
      at the payload URL (sw.js `notificationclick`). Easiest trigger: `POST /api/briefing/generate`.
- [ ] **Stranger-Danger critical alert** fires a push: drive `StrangerDangerService.scan()` to a new detection
      and confirm the "⚠️ Stranger danger" push arrives and deep-links to `/intelligence`.
- [ ] Expired-subscription pruning: a 404/410 from the push service deletes the row (`WebPushSender` → `EXPIRED`).

**Alert discipline (FR-18/19/20) — logic unit-tested on laptop; confirm live behaviour with real Redis:**
- [ ] **Dedup (8.4):** re-flagging the same stranger ticker within 30 min does NOT re-push (Redis key
      `argus:notif:dedup:*` with TTL); the collapsed count is logged. (CRITICAL bypasses the gate but still dedups.)
- [ ] **Fatigue gate (8.3):** non-critical alerts below `argus.notification.min-confidence`/`min-portfolio-impact`
      are suppressed and logged; CRITICAL always passes. Tune the thresholds against real signal volume.
- [ ] **Tier routing (8.2):** CRITICAL arrives with `requireInteraction` (stays until acted on); IMPORTANT pushes
      immediately; NORMAL/INFO do NOT push (they're left for the briefing/digest). Note: NORMAL→briefing and
      INFO→weekly-digest are currently *defer-and-log* — no queue is persisted, and only Stranger-Danger
      currently feeds the pipeline. Wiring recommendation/calendar producers + a real weekly digest is follow-up.

**Morning Briefing (FR-16):**
- [ ] `POST /api/briefing/generate` produces a sensible **model** narrative (local Gemma, BIG tier) — headline +
      2–4 sentence body grounded in portfolio value/health, overnight news count, recs, today's calendar.
      NB: the broken `gemma4` build (§1 CORRECTION) may make output junky — the deterministic fallback should
      still yield a clean briefing if the model/parse fails.
- [ ] `GET /api/briefing/latest` returns it (200) and the dashboard **BriefingCard** renders it pinned at top;
      before any run it returns **204** and the card shows the "arrives at 8am" empty state.
- [ ] The **08:00 America/Toronto** `@Scheduled` cron actually fires once on the box and pushes the headline.

## 4. General hardware/ops to confirm on the Mini
- [x] FileVault ON (secrets at rest — NFR-3) — confirmed on the Mini 2026-06-21.
- [x] Ollama runs as a background/login service (`brew services start ollama`) — survives reboots.
- [x] 24/7 operation: all 4 compose services set `restart: unless-stopped` (verified) — behave across reboots.

## 5. Story 7.1 — Ask AI recommendation chat (live model)  ✅ MOSTLY DONE 2026-06-25 (latency caveat)
The chat backend (`com.argus.conversation`) + the Ask-AI panel are built and fully tested on the
laptop via the `dev`-profile `MockChatModel` (no Ollama). The **first real backend→Ollama call**
goes through this endpoint (closes the open item from §2). Smoke-tested live on the Mini (`prod` +
`gemma4:12b`) 2026-06-25 via `POST /api/recommendations/1/chat`:
- [x] **Grounded** answer correctly cites the recommendation's signals/diagnostic (agent-1-news w0.9,
      agent-7-calendar w0.3 BEARISH), 75%/25% bull/bear, 29% confidence — **no hallucinated numbers**.
- [ ] **Latency: FAILS ≤15s — 35–55s.** NOT a hardware/streaming issue: the `gemma4` model build is
      broken (generates ~400 invisible junk tokens/call — see §1 CORRECTION). Accepted as a known issue
      for now (Haiku covers heavy lifting; replace the local model later). UI "warming up" state untested.
- [x] **Multi-turn follow-ups stay coherent** — tracked "that risk" → the earnings event across turns.
- [ ] Live UI check on the device (PIN login → Ask AI panel) still open; validated here via curl.
[Source: 7-1-recommendation-chat.md AC#4; epics.md#Story 7.1; docs/mac-mini-validation.md §1–2]

## 6. Story 7.2 — Portfolio chat (live model)  ⏳ TODO on the Mini
Dashboard "Ask AI" (TopBar) → `POST /api/portfolio/chat`, grounded in holdings + health + upcoming
calendar + recent recommendations + investor profile. Built + tested on the laptop via the dev
`MockChatModel`. On the Mini with `prod` + `gemma4:12b` (model switched 2026-06-24 — see §1 REVISION):
- [ ] Returns a **grounded** portfolio answer that cites holdings/health/calendar/recs (not hallucinated).
- [ ] **Latency:** the larger portfolio context still answers within **≤15s** warm (watch prompt size vs
      context window — grounding is capped at ~14-day calendar window + ≤10 recent recs).
- [ ] Sample questions behave (e.g. "What should I watch before the Fed meeting?", "Which holding concerns you most?").
[Source: 7-2-portfolio-chat.md AC#4; epics.md#Story 7.2]

## 7. Story 7.3 — Claude Haiku escalation (live API key)  ✅ DONE 2026-06-25
Validated live on the Mini 2026-06-25 (`ANTHROPIC_API_KEY` set; backend logged "Anthropic Haiku
escalation enabled"). `POST /api/recommendations/1/chat` with `deeper:true`:
- [x] **Real Claude Haiku answer** (`claude-haiku-4-5-20251001`) — high-quality structured analysis,
      correctly grounded in the signals, **5.6s** wall (the fast "heavy lifting" path).
- [x] **Cost logged:** `event=haiku_escalation model=claude-haiku-4-5-20251001 inputTokens=268
      outputTokens=321 costUsd=0.001873` — exact at $1/$5 per MTok.
- [x] **Sanitized context** — only 268 input tokens, no exact dollar amounts (escalate path uses the
      `sanitized=true` grounding; portfolio was empty here regardless).
- [ ] **On-failure fallback** (local model *errors* → Haiku) not force-tested yet; the explicit
      "deeper analysis" escalation (the main path) is confirmed. No-key → clean 503 was verified earlier.
[Source: 7-3-haiku-escalation.md AC#4/#6; epics.md#Story 7.3]

## 8. Epic 9 — Ops dashboards (9.1 live, 9.5 hardware, 9.7 freshness)  ⏳ confirm on the Mini
Built + statically verified on the laptop (backend `test-compile` green; `FreshnessServiceTest` +
`PerformanceServiceTest` pass; frontend `tsc` + `eslint` green). The analytics (9.2/9.3/9.4) are pure
logic and need no Mini step. These need the running stack / real hardware:
- [ ] **9.1 live agent status:** with the stack up, the Agents view updates over WebSocket — the backend
      pushes the fleet snapshot to `/topic/agents` every `argus.ops.agent-broadcast-ms` (15s). Confirm the
      cards refresh without a reload. (Richer ANALYZING/ERROR states + per-run duration/next-run are NOT
      built — they need per-agent run instrumentation; tracked as a follow-up.)
- [ ] **9.5 hardware:** `GET /api/ops/hardware` reports **real** values on the Mini — RAM vs 28GB, the data
      SSD vs 256GB (set `ARGUS_OPS_DATA_DIR` to the real data volume), CPU load. Confirm they look right
      under load. Still null/follow-up: per-component RAM (Postgres/Redis/model resident), SSD days-to-full
      (needs growth history), Neural-Engine load (not exposed to the JVM).
- [ ] **9.7 freshness:** `GET /api/ops/freshness` flags a source stale past its threshold — verify against
      real agent cadences and tune thresholds if needed. **Backup status is now built** (`BackupStatusService`
      feeds the Ops health card) but needs the external backup SSD to exercise — validated with 10.1/10.2 below.

## 9. Epic 10 — Resilience & budget  ⏳ confirm on the Mini
Built + statically verified on the laptop (`PlatformModeServiceTest` passes; backend `test-compile`,
frontend `tsc`/`eslint` green). Needs the running stack / real outage / external SSD:
- [ ] **10.4 Degraded Mode:** pull the Mini's network (or block `argus.resilience.probe-url`) and confirm
      after 2 failed probes the platform flips to DEGRADED — a CRITICAL push fires, the dashboard shows the
      "Offline — showing last-known data" banner (via `/topic/platform-mode`), and on reconnect it returns to
      NORMAL with the "back online / catching up" push. *Not built:* actually pausing each net-dependent
      ingestion agent (they should consult `PlatformModeService.isDegraded()` — integration follow-up).
- [ ] **10.6 Budget alerts:** drive paid spend across 70/80/95% and confirm `BudgetWatcher` pushes the
      NOTICE/WARNING/CRITICAL notifications once per escalation, and that ≥95% pauses cloud calls
      (`CostGovernor.allowPaidCall()` false → gateway stays local).
- [ ] **10.1 / 10.2 Automated backup + status (BUILT on the laptop — validate on the Mini):** the code exists
      (`scripts/backup.sh` = `pg_dump` 6h full + 15-min critical-tables; `scripts/install-backup-schedule.sh` =
      launchd; `BackupWatcher` = 🔴 disconnect / 🟡 stale alerts; `BackupStatusService` → Ops "System health"
      card). On the Mini with the external SSD mounted: run `install-backup-schedule.sh`, set `ARGUS_BACKUP_DIR`,
      and confirm (a) full + critical dumps land on the SSD at cadence, (b) the Ops card shows last success/size,
      (c) unplugging the SSD fires the 🔴 CRITICAL push once and recovery re-arms it. **Setup steps:**
      [`docs/backup-build-checklist.md`](backup-build-checklist.md).
- [ ] **10.3 Recovery drill:** once backups exist, follow [`/RECOVERY.md`](../RECOVERY.md) end-to-end on a
      scratch volume and confirm the documented data-loss bounds hold.

## 11. Multi-Bank — RBC import + cross-bank "Combined by account type"  ⏳ TODO on the Mini
Built + statically verified on the MacBook (backend `mvn -o compile test-compile` **green**; frontend
`tsc --noEmit` **green**). Adds RBC statement support, a first-class normalized account **type**, a
cross-bank rollup, and a dual-currency review guard. Needs the running stack (Postgres + Flyway + the
LLM parser via Ollama/Haiku + a price feed) — none exercisable on the MacBook. **The 4 RBC PDFs live in
`~/Downloads/RBC/` (copy them to the Mini).** Corrected inventory (each RBC account is DUAL-CURRENCY —
a CDN$ side + a U.S.$ side in one PDF):
- `Statement-2018` → **10264083 Canada Inc.** (incorporation) — Cash/Corporate. CAD: DOL + $2,337. USD: **$244,384 cash** + AMZN·GOOGL·AAPL·MSFT·NVDA·NKE·TSLA·TSM.
- `Statement-2725` → Gaurav Oli — Cash. CAD: ZGLD, XQQ + $30. USD: $1,898 + AMZN·GOOGL·MSFT·NVDA·SMCI·WMT.
- `Statement-4079` → Gaurav Oli — RRSP. CAD: empty. USD: $10,703 + SPCX ×27.
- `Statement-8511` → Gaurav Oli — TFSA. CAD: empty. USD: $60 + GOOGL ×34.

**Migration:**
- [ ] `V42__account_type.sql` applies on boot (Flyway) — adds `account_meta.account_type`.

**RBC import (Holdings tab → pick "RBC" → upload each PDF → Confirm; LLM parser is the default):**
- [ ] Each PDF parses **BOTH** the CDN$ and U.S.$ sub-statements — holdings tagged with the right currency, not collapsed as a duplicate date. All securities captured incl. the "Other"/ADR row (TSM) in `2018`.
- [ ] **Owner** is correct: `2018` → owner `10264083 Canada Inc.`, ownerType **Corporate** (owner is the company, not the ATTN person); the other three → `Gaurav Oli`, Solo.
- [ ] **Type** is correct: `2018`/`2725` → Cash, `4079` → RRSP, `8511` → TFSA (derived from the statement header, not the account number).
- [ ] USD-side book cost has no purchase date → ACB uses **estimated FX** (flagged `fxEstimated`); confirm the estimate looks right and that per-holding FX can be set via `confirmFx`.

**Dual-currency review guard (`PortfolioImportService` + `AccountLabels.baseKey`):**
- [ ] Re-import a statement with one currency side removed/empty → the holdings on the missing side get `needsReview=true` and a `log.warn` ("a currency side of a dual-currency account was absent") fires — not silently left stale.

**Combined-by-account-type rollup (Holdings tab, `HoldingsTable.tsx`):**
- [ ] The **"Combined by account type"** section sums the same registration ACROSS banks within an owner (e.g. Gaurav's NBDB TFSA + RBC TFSA → one TFSA line), in CAD, with Invested / Market / **Cash** / Gain-Loss columns; expand drills into each bank account.
- [ ] The **incorporation stays its own owner** — never merged into personal totals.
- [ ] CAD↔USD uses the **latest** BoC USD/CAD (already confirmed in code: `usdCadOn(today)` → nearest prior business day). Sanity-check the converted numbers vs the statement's own FX line (1 USD = 1.42015 CAD on 2026-06-30).

**Pricing (expect gaps until the feed covers them):**
- [ ] Confirm which RBC tickers price live and which stay unpriced: `SPCX` (SpaceX proxy), the `TSM` ADR, and TSX tickers `ZGLD`/`XQQ` need the TSX feed; unpriced positions show cost with no market value.
[Source: multi-bank RBC support; `com.argus.portfolio` (LlmStatementParser, AccountLabels, AccountMeta, PortfolioImportService, LivePortfolioService); `V42__account_type.sql`]

## 12. Health-score bands — unified score→color mapping  ⏳ visual check on the Mini
Presentation-only refactor (backend scores unchanged). `frontend/src/lib/scoreBands.ts` is now the single
source of truth for mapping a 0–100 score to a band; the health **ring** (`HealthScoreRing`), the top-bar
**badge** (`HealthScoreBadge`), and the **breakdown** popover previously hardcoded three different threshold
sets (75/50, 80/60, …) and could disagree. Statically verified on the MacBook (`tsc`/`eslint` **green**);
colors only render in the running app, so:
- [ ] The ring, the top-bar badge, and the breakdown all agree on the **same band** for the same score
      (e.g. a 72 reads "Balanced"/amber everywhere, not "Healthy" in one place and "Balanced" in another).
- [ ] Band boundaries look right: **≥75 Healthy** (green), **50–74 Balanced** (amber), **<50 At risk** (red),
      and a null/absent score shows the neutral "—" state.
- [ ] The breakdown popover shows the new footnote — "Rule-based score … Agent-sentiment and open-risk
      inputs are not yet included."
[Source: health-score band unification; `frontend/src/lib/scoreBands.ts`, `HealthScoreRing.tsx`, `HealthScoreBadge.tsx`, `HealthScoreBreakdown.tsx`]

## 13. Personas — consensus summary + Canadian lens (Epic 7, Stories 7.4/7.5)  ⏳ real-model check on the Mini
Logic verified on the MacBook (`CanadianContextServiceTest` 4/4; `tsc`/`eslint` **green**), but persona
verdicts come from the local BIG model (gemma) — quality/format only observable on the running stack:
- [ ] **Consensus summary (7.4):** each rec card's "What the personas think" shows a one-line consensus
      derived from the four stances (e.g. "leaning support (3 agree, 1 caution)") coloured by the lean;
      the 🔄 warming state polls then fills in.
- [ ] **Canadian lens (7.5, FR-34):** for a **US-listed** rec (e.g. NVDA/AAPL) the Canadian persona's
      verdict actually **cites the CAD-equivalent** of the price target (live BoC USD/CAD) **and** the
      account-specific US-withholding note (15%, waived in RRSP, unrecoverable in TFSA) — not invented
      numbers. For a **CAD-listed** name (e.g. ZGLD/XQQ) it should say no US withholding applies.
- [ ] The CAD-equivalent tracks the live rate (sanity-check vs BoC), and unpriced names (e.g. SPCX) still
      surface the US-listed withholding note (the safe default).
[Source: Epic 7 personas; `com.argus.persona` (PersonaService, CanadianContextService), `RecommendationCards.tsx`]

---
_Keep this list updated as stories add Mini-only validation. Backup/recovery
validation has its own runbook (`/RECOVERY.md`, Epic 10, Story 10.3)._
