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
> predictable**. Trade-off: 12B is **slower per token (~12.5 vs ~28 tok/s)** because it's dense vs the
> 26B MoE, so a long ~380-token answer is still ~30s — but predictably. Closing the ≤15s gap for long
> answers needs the **token-streaming** work deferred in `deferred-work.md` (story 7.1); short answers
> already pass. `.env` `ARGUS_BIG_MODEL=gemma4:12b`; 26B stays the quality target for when the box has
> dedicated RAM (it's the deploy box — close IntelliJ/dev apps). Committed prod defaults still say 26b;
> revisit them when streaming lands or the RAM picture changes.

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

## 3. PWA / Web Push prerequisite (Epic 8, later)
- [ ] Confirm the Tailscale HTTPS origin is valid so the service worker + Web Push
      (FR-17, Epic 8) can register on the iPhone. (HTTPS is set up in step 2.)

## 4. General hardware/ops to confirm on the Mini
- [x] FileVault ON (secrets at rest — NFR-3) — confirmed on the Mini 2026-06-21.
- [x] Ollama runs as a background/login service (`brew services start ollama`) — survives reboots.
- [x] 24/7 operation: all 4 compose services set `restart: unless-stopped` (verified) — behave across reboots.

## 5. Story 7.1 — Ask AI recommendation chat (live model)  ⏳ TODO on the Mini
The chat backend (`com.argus.conversation`) + the Ask-AI panel are built and fully tested on the
laptop via the `dev`-profile `MockChatModel` (no Ollama). The **first real backend→Ollama call**
goes through this endpoint (closes the open item from §2). On the Mini, with the `prod` profile +
`gemma4:12b` (model switched 2026-06-24 — see §1 REVISION):
- [ ] `POST /api/recommendations/{id}/chat` returns a **grounded** answer that correctly cites the
      recommendation's signals/diagnostic + the portfolio (not hallucinated numbers).
- [ ] **Latency:** warm response within the **≤15s** target (NFR / A-9); confirm the UI "warming up"
      indicator covers the **~28s cold-load** when the model was idle-unloaded (`ARGUS_MODEL_KEEP_ALIVE=5m`).
- [ ] Multi-turn follow-ups stay coherent (the client resends history each turn — server is stateless).
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

## 7. Story 7.3 — Claude Haiku escalation (live API key)
Unlike the local-model paths, the Haiku escalation is **API-based and can be exercised on the
laptop** with a real key — but it's left key-gated by default (tested with a mock / no-key 503).
With `ANTHROPIC_API_KEY` set (laptop **or** Mini):
- [ ] "Get deeper analysis" in either chat → a real **Claude Haiku** answer (model `claude-haiku-4-5-20251001`).
- [ ] The per-call **cost is logged** (`event=haiku_escalation … costUsd=…`, $1/$5 per MTok from response usage).
- [ ] The escalation prompt is **sanitized** — confirm no exact dollar amounts / share counts leave the network (only weights %, tickers, health, probabilities).
- [ ] **On the Mini only:** the local-model **on-failure fallback** (gemma4:26b errors → Haiku) returns a Haiku answer when keyed, and a clean **503** when not (no stub text).
[Source: 7-3-haiku-escalation.md AC#4/#6; epics.md#Story 7.3]

---
_Keep this list updated as stories add Mini-only validation. Backup/recovery
validation has its own runbook (Epic 10, Story 10.3)._
