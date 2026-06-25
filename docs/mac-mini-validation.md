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

## 3. PWA / Web Push prerequisite (Epic 8, later)
- [ ] Confirm the Tailscale HTTPS origin is valid so the service worker + Web Push
      (FR-17, Epic 8) can register on the iPhone. (HTTPS is set up in step 2.)

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

---
_Keep this list updated as stories add Mini-only validation. Backup/recovery
validation has its own runbook (Epic 10, Story 10.3)._
