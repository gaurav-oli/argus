# Mac Mini — Pending Hardware Validation

Things that **can only be run/verified on the Mac Mini M3 (28GB)** and were
therefore deferred during laptop development. Work through this checklist when
you're on the Mini. Each item links back to its story.

> Why deferred: the dev laptop is a MacBook Pro M5 / 16GB — it cannot load the
> 26B model and is not the deployment target. Everything below was either built
> + verified *structurally* on the laptop, or explicitly gated on the hardware.

## 1. Story 1.3 — RAM + latency validation spike (GAP-1/2)  ⛳ blocking
Status: **backlog** (deferred). This is the one true blocker before heavy feature work.
- [ ] Install Ollama natively; `ollama pull gemma3:27b` (or candidate tag).
- [ ] Load the 26B MoE under worst case (Postgres + Redis + JVM + a generation request).
- [ ] Record total resident RAM + headroom against the **28GB ceiling**.
- [ ] Measure Ask-AI first-token + full-response latency against the **≤15s** target.
- [ ] If RAM margin or latency fails → revise model / keep-alive policy and re-test.
- [ ] Set the validated tag in `.env` via `ARGUS_BIG_MODEL` (+ `ARGUS_MODEL_KEEP_ALIVE`).
- [ ] Update sprint-status `1-3-…` → done.
[Source: epics.md#Story 1.3; architecture.md#GAP-1/2]

## 2. Story 1.8 — deploy + Tailscale, real run  (done in code; manual run pending)
The artifacts (Dockerfiles, compose `deploy` profile, runbook) are built + verified
on the laptop. These steps need the Mini. Full procedure: **`docs/deploy-runbook.md`**.
- [ ] On the Mini: `git pull` → `docker compose --profile deploy up -d --build`.
- [ ] Confirm the **`prod` profile** boots end-to-end (Flyway migrates; backend reaches
      native Ollama via `host.docker.internal`) — only the `dev`/mock path was run on the laptop.
- [ ] `tailscale up` + MagicDNS; `tailscale cert` + `tailscale serve` HTTPS (tailnet-only).
- [ ] **Verify the `tailscale serve` path syntax** for your Tailscale version (it varies — see runbook caveat) and that `/api` + the `/ws` WebSocket upgrade both work.
- [ ] Open `https://<mini>.<tailnet>.ts.net` **on the iPhone** → dashboard shell loads.
- [ ] Confirm REST **and live WebSocket** round-trip through the deployed stack.
- [ ] Confirm **no public exposure**: `tailscale funnel status` off; published ports loopback-only.
- [ ] Build the frontend image with single-origin args (`NEXT_PUBLIC_API_BASE_URL=` empty, `NEXT_PUBLIC_WS_URL=wss://<host>/ws`).
[Source: 1-8-tailscale-access-deploy-to-mini-runbook.md AC#6]

## 3. PWA / Web Push prerequisite (Epic 8, later)
- [ ] Confirm the Tailscale HTTPS origin is valid so the service worker + Web Push
      (FR-17, Epic 8) can register on the iPhone. (HTTPS is set up in step 2.)

## 4. General hardware/ops to confirm on the Mini
- [ ] FileVault ON (secrets at rest — NFR-3).
- [ ] Ollama runs as a background/login service so it survives reboots.
- [ ] 24/7 operation: containers `restart: unless-stopped` behave across reboots.

---
_Keep this list updated as stories add Mini-only validation. Backup/recovery
validation has its own runbook (Epic 10, Story 10.3)._
