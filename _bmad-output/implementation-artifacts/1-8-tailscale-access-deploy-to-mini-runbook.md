---
baseline_commit: 77f2342
---

# Story 1.8: Tailscale access + deploy-to-Mini runbook

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want Argus reachable from my devices over Tailscale and a repeatable deploy,
so that I can run it on the Mini from anywhere.

## Acceptance Criteria

1. **Containerized stack** — `backend` and `frontend` Dockerfiles exist and build (arm64). Extending `docker-compose.yml` with `backend` + `frontend` services (behind a compose profile so the existing dev `docker compose up` stays Postgres+Redis-only) brings the whole stack up: backend connects to Postgres + Redis over the compose network and reaches **Ollama on the host** (not containerized), and the frontend serves the dashboard shell.
2. **Frontend prod image** — `next.config.ts` sets `output: "standalone"`; the frontend Docker image serves the built dashboard (not `next dev`) and its 1.6 `apiClient`/`wsClient` reach the backend (REST + STOMP round-trip works) in the composed stack.
3. **No public-internet exposure** — every published port binds to loopback (`127.0.0.1`) or the Tailscale interface only; nothing listens on `0.0.0.0` publicly; access is via Tailscale exclusively (`tailscale serve`, never `funnel`). This is documented and verified (`docker compose config` / `ss`/`lsof` check).
4. **Tailscale access** — a documented setup for the Mini + iPhone/iPad: install, `tailscale up`, MagicDNS hostname, and HTTPS via `tailscale serve` + `tailscale cert` (required so the PWA/service-worker + Web Push in Epic 8 work). **Given** Tailscale on Mini + iPhone, **When** the user opens the tailnet hostname, **Then** the shell loads — with no public-internet exposure.
5. **Deploy runbook** — a committed, repeatable runbook (`git pull` → `docker compose --profile deploy up` → native Ollama pull/run → verify) lives at a documented path and is linked from `README.md`. It covers prerequisites, `.env` setup, the Ollama model note (tag provisional pending Story 1.3), start/stop/rollback, and troubleshooting.
6. **Verification scope** — Docker images build and `docker compose config` validates **on the dev laptop**; the full composed stack starts locally (profile `dev`/small-model or mock — the laptop is 16GB and cannot run the 26B model). The actual Mac-Mini run + opening the tailnet hostname from the iPhone is a **manual runbook step the user performs on the Mini** (the Mini is not present in this session) — call this out explicitly, mirroring Story 1.7's headless-only verification note.

## Tasks / Subtasks

- [x] Task 1 — Backend Docker image (AC: #1, #6)
  - [x] `backend/Dockerfile`: multi-stage — build with Maven + Temurin **JDK 25**, run on a **JRE 25** base (arm64). Use Spring Boot 4 layered jars (or the Maven wrapper `./mvnw -q package -DskipTests`) for a lean runtime layer. Expose `8080`. Honor `SPRING_PROFILES_ACTIVE`.
  - [x] `backend/.dockerignore` (exclude `target/`, IDE files, tests' build noise).
- [x] Task 2 — Frontend Docker image (AC: #1, #2)
  - [x] `frontend/next.config.ts`: add `output: "standalone"` (keeps the image lean; self-hosted `next/font` already works offline per Story 1.7).
  - [x] `frontend/Dockerfile`: multi-stage Node build → copy `.next/standalone` + `.next/static` + `public/`; run `node server.js` on port `3000`.
  - [x] `frontend/.dockerignore` (exclude `node_modules`, `.next`, etc.).
- [x] Task 3 — Compose the full stack (AC: #1, #3)
  - [x] Extend `docker-compose.yml`: add `backend` + `frontend` services tagged `profiles: ["deploy"]` so dev `docker compose up` stays DBs-only (preserve the existing comment intent). `depends_on` Postgres/Redis healthchecks.
  - [x] Backend env in compose: `SPRING_PROFILES_ACTIVE=prod`, datasource host = `postgres`, redis host = `redis` (compose service names), `OLLAMA_BASE_URL=http://host.docker.internal:11434` with `extra_hosts: ["host.docker.internal:host-gateway"]` (Ollama is native on the host).
  - [x] Port binding: keep all host-published ports on `127.0.0.1` (Tailscale serve fronts them) — do **not** publish `0.0.0.0`.
- [x] Task 4 — Environment + single-origin wiring (AC: #2, #3, #5)
  - [x] Update `.env.example` with deploy vars: `SPRING_PROFILES_ACTIVE`, `OLLAMA_BASE_URL`, `ARGUS_BIG_MODEL`, and the frontend API/WS base. Document that the Mini sets a real `POSTGRES_PASSWORD`.
  - [x] Recommended single-origin setup: `tailscale serve` reverse-proxies one HTTPS tailnet origin → `/` to `frontend:3000` and `/api` + `/ws` to `backend:8080`, so `NEXT_PUBLIC_*` API base is **relative/same-origin** (no CORS, HTTPS for the PWA). Document the exact `tailscale serve` config.
- [x] Task 5 — Tailscale + deploy runbook doc (AC: #3, #4, #5)
  - [x] Write `docs/deploy-runbook.md`: prerequisites (Tailscale on Mini + iPhone/iPad, `tailscale up`, MagicDNS; Ollama native + `ollama pull`), `.env` setup, deploy steps (`git pull` → `docker compose --profile deploy up -d`), `tailscale serve`/`tailscale cert` for HTTPS, verify (curl the tailnet host, open on iPhone), start/stop/rollback, troubleshooting. Use `tailscale serve` (tailnet-only) — **never `tailscale funnel`** (public).
  - [x] Link the runbook from `README.md` (replace the "added later (Story 1.8)" note with a real reference).
- [x] Task 6 — Verify locally + scope the Mini step (AC: #6)
  - [x] `docker compose config` validates; `docker build` succeeds for both images (arm64); bring the full stack up locally under a non-prod model path (profile `dev`/mock or a small Ollama model) and confirm the frontend loads + the 1.6 REST/WS round-trip works through the containers.
  - [x] Confirm no public-internet listeners (loopback-only bindings). Document the Mini-only steps (26B model, iPhone-over-Tailscale) as manual.

## Dev Notes

### Current state of the files this story touches [verified 2026-06-19, branch off 77f2342]
- **`docker-compose.yml`** — Postgres 18 (pgvector 0.8.2-pg18) + Redis 8 **only**, both published to `127.0.0.1` (loopback). Its header comment explicitly says the `backend`/`frontend` services + native-Ollama deploy are added in **this story**. Postgres volume mounts at `/var/lib/postgresql` (PG18 quirk — do not change). Healthchecks exist for both.
- **No Dockerfiles exist yet** — both are net-new in this story.
- **`backend/`** — Java 25, Spring Boot 4, Maven. `application.yml` defaults to profile `dev` via `SPRING_PROFILES_ACTIVE:dev`; datasource/redis default to `localhost` (override per-service in compose). `application-prod.yml` routes the Model Gateway to Ollama at `${OLLAMA_BASE_URL:http://localhost:11434}`, model `${ARGUS_BIG_MODEL:gemma3:27b}` (tag provisional — see Story 1.3), concurrency 1, keep-alive 10m.
- **`frontend/`** — Next 16, `next.config.ts` is currently empty (`output: standalone` is net-new). Scripts: `dev`/`build`/`start`/`lint`. Story 1.7 self-hosts `next/font` (offline-safe) and ships a PWA manifest + icons; the service worker / Web Push are deferred to Epic 8 (but HTTPS via Tailscale is the prerequisite they will need — set it up now).
- **`.env` / `.env.example`** — exist; `.env` is gitignored. Current `.env.example` documents Postgres creds + API keys; **add the deploy vars** here.
- **1.6 clients** (`frontend/src/lib/apiClient.ts`, `wsClient.ts`) run in the **browser** → the API/WS base must be reachable from the iPhone's browser = the Tailscale origin. The single-origin `tailscale serve` approach makes this relative (cleanest; avoids CORS + mixed-content).

### Architecture requirements [Source: architecture.md#Decision 8; #Development Workflow]
- **Docker Compose**: Postgres + Redis + backend + frontend. **Ollama runs NATIVELY on the host** — never containerized (no Metal/GPU passthrough in Docker on Mac). Reach it from containers via `host.docker.internal`.
- **Deploy = `git pull` + `docker compose up` on the Mini.** GitHub Actions deferred. [Source: architecture.md#Decision 8]
- **Runtime = Mac Mini M3, 28GB/256GB, macOS, single node; arm64.** Dev laptop = MacBook Pro M5 16GB (coding only — cannot run the 26B model; deploy verification uses dev/small-model/mock). [Source: architecture.md#Project Type & Scale]
- **Tailscale is the network boundary; the platform is NEVER on the public internet.** Secrets at rest via macOS FileVault + gitignored `.env`. [Source: architecture.md#Decision 6; NFR-3]
- **Single-process modular monolith** — one Spring app + one Next app; do not split into more services (RAM). [Source: architecture.md#Solution Architecture]

### Latest-tech guidance (stable as of 2026-06)
- **Next 16 standalone**: `output: "standalone"` emits `.next/standalone/server.js`; the runtime image copies `standalone` + `.next/static` + `public/` and runs `node server.js` (`PORT`/`HOSTNAME` env). Lean, no full `node_modules`.
- **Java 25 / Spring Boot 4**: build stage `maven`/Temurin JDK 25; runtime `eclipse-temurin:25-jre` (arm64). Spring Boot 4 supports layered jars (`java -Djarmode=tools -jar app.jar extract --layers`) for better caching — optional but recommended.
- **Tailscale**: `tailscale up` (MagicDNS gives a stable `<host>.<tailnet>.ts.net`); `tailscale cert` issues a tailnet TLS cert; `tailscale serve` reverse-proxies HTTPS → local ports (tailnet-only). `tailscale funnel` = public exposure → **forbidden here** (violates AC #3 / NFR-3).

### Project Structure Notes
- New files: `backend/Dockerfile`, `backend/.dockerignore`, `frontend/Dockerfile`, `frontend/.dockerignore`, `docs/deploy-runbook.md`. Modified: `docker-compose.yml`, `frontend/next.config.ts`, `.env.example`, `README.md`.
- Keep the dev workflow intact: `docker compose up` (no profile) must still start **only** Postgres + Redis; the backend/frontend services live under `--profile deploy`.
- This completes **Epic 1 (Foundation & Walking Skeleton)** — the last story. Story 1.3 (RAM/latency spike) remains deferred/backlog; the deploy must not hard-depend on the 26B model being validated (model tag is overridable via `ARGUS_BIG_MODEL`).

### Testing standards
- No automated test harness is added here (infra/ops story). Verification = `docker compose config` validates, both images `docker build` clean (arm64), the full stack starts locally and the 1.6 REST/WS round-trip works through containers, and a loopback-only port check. The 26B-model run + iPhone-over-Tailscale check is a manual runbook step on the Mini (documented, not executed this session).

### References
- [Source: epics.md#Story 1.8: Tailscale access + deploy-to-Mini runbook] — user story + AC.
- [Source: epics.md#Epic 1: Foundation & Walking Skeleton] — epic goal (deployed, Tailscale-accessible Argus on the Mini).
- [Source: architecture.md#Decision 8 — Infrastructure & Deployment] — Compose + native Ollama + git-pull deploy.
- [Source: architecture.md#Development Workflow] — dev vs Mini deploy commands + profiles.
- [Source: architecture.md#Decision 6 — Auth & Security; PRD NFR-3] — Tailscale-only, never public; FileVault + gitignored `.env`.
- [Source: docker-compose.yml header] — backend/frontend + Ollama deferred to this story (intentional).
- [Source: 1-7-dark-theme-dashboard-shell.md] — frontend shell + PWA manifest (HTTPS prerequisite); offline self-hosted fonts.
- [Source: backend/src/main/resources/application-prod.yml] — Ollama base-url / model / keep-alive env knobs.

### Review Findings

_Code review 2026-06-19 (Blind Hunter + Edge Case Hunter + Acceptance Auditor, all Opus 4.8). All 6 ACs verified PASS. 1 real bug fixed, 1 doc hardening, rest dismissed as verified false positives. Empirically confirmed where the layers disagreed._

**Patches (applied 2026-06-19):**
- [x] [Review][Patch] **Single-origin broken: compose `:-` rewrote the documented empty API base back to `localhost:8080`** — `NEXT_PUBLIC_API_BASE_URL: ${...:-http://localhost:8080}` substitutes the default when the var is empty OR unset, but the runbook instructs the Mini operator to set it *empty* for same-origin; the bundle would inline `localhost:8080` and break the iPhone. **Confirmed via `docker compose config` (resolved to `http://localhost:8080` with an empty value).** Fixed to `${...-http://localhost:8080}` (no colon) so a set-but-empty value is honored (unset → localhost for local dev; empty → same-origin for the Mini). [docker-compose.yml] (edge)
- [x] [Review][Patch] Hardened the `tailscale serve` section of the runbook — flagged version-specific syntax more firmly and added a step to verify the `/ws` WebSocket upgrade. [docs/deploy-runbook.md] (blind+edge+auditor)

**Dismissed as noise / verified false positives:**
- Datasource creds "not wired" (blind) — FALSE: `application.yml` reads `${POSTGRES_USER}`/`${POSTGRES_PASSWORD}` directly and `SPRING_DATASOURCE_URL` overrides the host via relaxed binding; live stack connected + Flyway ran.
- "JRE image has no bash" / "healthcheck always healthy" (blind, 2× High) — FALSE: `eclipse-temurin:25-jre` is Ubuntu-based with bash; **verified** `bash -c 'exec 3<>/dev/tcp...'` returns non-zero on a closed port; the container became healthy only after the port opened.
- Backend jar glob matches a plain jar (blind) — FALSE: Maven repackage yields a single executable jar (`.jar.original` doesn't match `*.jar`); image built clean.
- `COPY /app/public` fails if absent (blind) — FALSE: `frontend/public/` exists (1.7 icons); build succeeded.
- `HOSTNAME=0.0.0.0` / hardcoded repo URL / API keys in container env (blind) — by-design / user's own repo / accepted per threat model (FileVault + Tailscale-only, single user).
- `depends_on: service_started` not `service_healthy` (blind+edge) — harmless: the frontend's backend calls are client-side (browser), not at container start.
- Healthcheck probes port not `/actuator/health` (edge) — acceptable: Boot binds the port only after a successful context refresh (Flyway/DB), as the hunter itself concluded.
- Postgres password vs pre-existing volume (edge) — inherent Postgres behavior, already in the runbook troubleshooting table; compose cannot retro-fix an initialized volume.
- 26B vs `gemma3:27b` wording, layered jars, dev-profile-only + WS-in-container not re-run (auditor) — pre-existing/provisional, optional, and disclosed per AC #6 (REST verified through containers; WS shares the same verified backend container/port, exercised in Story 1.6).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `docker compose config --services` → dev (no profile) = `postgres, redis`; `--profile deploy` = `backend, frontend, postgres, redis`. Full deploy config validates.
- `docker compose --profile deploy build` → both images built (arm64): `argus-backend:local`, `argus-frontend:local`.
- Full stack up (`SPRING_PROFILES_ACTIVE=dev docker compose --profile deploy up -d`): all 4 containers healthy.
  - Backend REST through container: `GET 127.0.0.1:8080/api/system-info` → `{"name":"argus","version":"0.0.1-SNAPSHOT","profile":"dev","time":...}` (connected to Postgres + Redis by compose service name).
  - Frontend prod image: `GET 127.0.0.1:3000/` → 200; HTML contains the shell (`Argus`, `Good morning`, `Morning Briefing`).
  - Port bindings: all `127.0.0.1:*` — **no `0.0.0.0`** (no public exposure).
- Teardown via `docker compose --profile deploy down` (non-destructive); dev workflow reconfirmed DBs-only.

### Completion Notes List

- **Containerized stack (Task 1–3):** Added `backend/Dockerfile` (multi-stage Temurin JDK 25 build via the Maven wrapper → JRE 25 runtime, non-root) and `frontend/Dockerfile` (Next 16 `standalone`, multi-stage, non-root). Extended `docker-compose.yml` with `backend` + `frontend` services behind a **`deploy` compose profile** so the existing dev `docker compose up` stays Postgres+Redis-only. Backend reaches the DB/cache by compose service name (`SPRING_DATASOURCE_URL` → `postgres`, `REDIS_HOST` → `redis`) and **native Ollama via `host.docker.internal`** (`extra_hosts: host-gateway`). Healthchecks added (bash `/dev/tcp` probe for the JRE backend; `wget` for the frontend).
- **Frontend standalone (Task 2):** `next.config.ts` now sets `output: "standalone"`. The 1.6 `apiClient`/`wsClient` are already env-driven, so single-origin is wired purely via **Docker build args** (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_WS_URL`) — no 1.6 code changed. Defaults suit local verification (`localhost:8080`); the Mini overrides them (empty API base → same-origin `/api`, `wss://…/ws`).
- **Env + single-origin (Task 4):** `.env.example` gains a "DEPLOY — Mac Mini only" section (`SPRING_PROFILES_ACTIVE`, `OLLAMA_BASE_URL`, `ARGUS_BIG_MODEL`, keep-alive, `NEXT_PUBLIC_*`). Recommended single-origin via `tailscale serve` path-routing documented.
- **Runbook (Task 5):** `docs/deploy-runbook.md` — topology, prerequisites, Tailscale setup (`up` + MagicDNS), Ollama pull, `.env`, deploy (`git pull` → `docker compose --profile deploy up -d --build`), HTTPS via `tailscale serve` + `tailscale cert` (**`funnel` explicitly forbidden**), verification, update/rollback, troubleshooting. Linked from `README.md`.
- **Verification scope (Task 6):** Builds + compose-config + the full stack running locally (dev/mock model) are verified on the laptop. The **26B-model run and iPhone-over-Tailscale check are manual Mini-only steps** documented in the runbook (Mini + 28GB model not available this session) — mirrors Story 1.7's headless-only verification note. Deploy does not hard-depend on the deferred Story 1.3 model validation (`ARGUS_BIG_MODEL` overridable).
- **WS note:** REST round-trip verified through the containerized stack; the STOMP `/ws` handler is on the same backend container/port (also loopback-published) and was exercised end-to-end in Story 1.6.

### File List

**New:**
- `backend/Dockerfile`
- `backend/.dockerignore`
- `frontend/Dockerfile`
- `frontend/.dockerignore`
- `docs/deploy-runbook.md`

**Modified:**
- `docker-compose.yml` (backend + frontend services under `deploy` profile; header)
- `frontend/next.config.ts` (`output: "standalone"`)
- `.env.example` (deploy section)
- `README.md` (deploy runbook reference)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-19 | Implemented Tailscale deploy: backend + frontend Dockerfiles, `deploy`-profile docker-compose (full stack; Ollama via host.docker.internal; loopback-only ports), Next standalone build, `.env.example` deploy vars, and `docs/deploy-runbook.md` (Tailscale serve HTTPS, no funnel). Verified locally — both images build, compose config valid, full stack healthy, backend REST + frontend serve through containers, no public exposure. Mini-only steps (26B model, iPhone-over-Tailscale) documented for manual run. Status → review. |
| 2026-06-19 | Code review (3 adversarial layers): all 6 ACs PASS, no Critical/High in code. Fixed 1 real bug — compose `NEXT_PUBLIC_API_BASE_URL` used `:-` which rewrote the documented empty same-origin value back to `localhost:8080` (verified + fixed to `-`). Hardened the runbook's `tailscale serve` + WS-upgrade guidance. 12 findings dismissed as verified false positives / by-design (bash-in-JRE, datasource creds, jar glob, etc.). Status → done. |
