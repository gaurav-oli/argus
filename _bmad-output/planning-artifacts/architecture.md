---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "_bmad-output/planning-artifacts/prds/prd-ProjectX-2026-06-15/prd.md"
  - "_bmad-output/planning-artifacts/brainstorming/brainstorming-session-2026-06-11-2231.md"
workflowType: 'architecture'
lastStep: 8
status: 'complete'
completedAt: '2026-06-17'
project_name: 'Argus'
user_name: 'Gaurav.oli'
date: '2026-06-15'
---

# Architecture Decision Document — Argus

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

The platform must support five distinct workload shapes — the combination is what makes Argus architecturally hard (the difficulty is concurrency + resource contention on fixed hardware, not scale):

1. **Real-time streaming** (FR-2, FR-24, FR-27) — sub-second price ticks and live dashboard updates via WebSocket → event-driven, always-connected backend.
2. **Continuous background agents** (FR-8, FR-14, FR-21) — long-running scheduled + event-triggered loops → Project Loom virtual-threads use case (many concurrent, mostly-I/O-bound agents on one JVM).
3. **Heavy local LLM inference** (FR-11, FR-12, FR-30, FR-33) — the binding constraint. Multiple consumers competing for one large model that cannot co-reside with everything else in 28GB.
4. **Event-driven orchestration** (FR-14) — Redis pub/sub fan-out: Agent 1 detects → Agent 5 wakes → notification fires, budgeted to ~25 min end-to-end.
5. **Stateful trust + judgment machine** (FR-11, FR-12, FR-26b) — Agent 5's Shadow→Probation→Active→Frozen graduation plus model-derived (not LLM-generated) probabilities that must be calibration-tracked.

**Non-Functional Requirements that most shape architecture:**
- **Hard 28GB RAM ceiling (on the Mac Mini runtime, not the dev laptop)** — the dominant constraint; dictates the model loading/eviction policy before anything else.
- **Hard $100 CAD/month budget** — drives local-first design and the Cost Governor's automatic fallback to local models.
- **Privacy** — data never leaves the home network except sanitized analysis context to Claude Haiku (never raw positions).
- **Local-first reliability** — Degraded Mode must run autonomously through internet outages; Tailscale-only, never public internet.
- **Auto-recovery** — agents self-retry and self-report without intervention (single operator).
- **Honesty/calibration** — probabilities are model-derived and calibration-tracked; small-sample win rates explicitly flagged as not statistically meaningful.

### Scale & Complexity
- **Primary domain:** Full-stack, local-first, event-driven AI system (backend-heavy).
- **Complexity level:** High — driven by concurrency, model resource contention, and heterogeneous AI orchestration on fixed hardware; NOT by user/data scale (one user, modest volume).
- **Estimated architectural components (MVP):** ~9 — agent runtime/scheduler, model gateway (Ollama + Haiku routing), event bus (Redis), data layer (Postgres + Mongo + vector), market-data ingestion (Finnhub WS), notification service (Web Push), API/WebSocket gateway, Next.js PWA, backup/ops service.

### Technical Constraints & Dependencies
- **Runtime target:** Mac Mini M3, 28GB / 256GB, macOS, single node. **Dev machine:** MacBook Pro M5, 16GB — coding only; both arm64 (clean dev/prod parity).
- **Deployment:** git-based — laptop → GitHub (`gaurav-oli/argus`) → Mac Mini `git clone`/pull → `docker compose up` (DBs + backend + frontend) + Ollama native. Tailscale links laptop + Mini + iPhone/iPad.
- **Ollama runs natively on the host (never containerized)** — Docker on Mac has no Metal/GPU passthrough.
- **Versions pinned to current (June 2026):** Java 25 LTS, Spring Boot 4.0.x, Next.js 16, Node 22 LTS, PostgreSQL 18, MongoDB 8.3, Redis 8, Tailwind v4. No hardcoded model version strings.
- **Local models:** Gemma 4 26B MoE (deep-analysis workhorse; ~15–16GB, ~4B active params/token = faster + lighter than 27B-dense), Gemma 4 E4B / Llama 3.2 3B (high-frequency agents).
- **External APIs:** Finnhub is the linchpin (prices/news/FX/VIX/earnings/IPO) — a single-point-of-failure to design fallbacks around; plus Claude Haiku (paid, Agent 5 output), GDELT/SEC/RSS (free, no key).
- **LLM access behind a single swappable interface** with dev/prod profiles (small model or mock on the laptop; 26B MoE on the Mini) so coding is never blocked on the Mini.

### Cross-Cutting Concerns
1. **Model resource governance** — *the* keystone (loading/eviction/queuing across Agent 4, Agent 5, Ask AI, Personas) — open question #6.
2. **Cost governance** — real-time spend tracking + automatic local fallback, woven through every cloud call.
3. **Event bus & latency budget** — Redis pub/sub as the spine; the ~25-min news→notification path is a design target.
4. **Graceful degradation** — Degraded Mode and auto-recovery as first-class states.
5. **Observability** — the System Ops Dashboard means the platform emits structured self-metrics from day one.
6. **Data lifecycle** — 256GB SSD with growing news/document stores; retention policy needed early.
7. **Probability provenance & calibration** — numbers are model-derived and auditable, never LLM-emitted.

## Starter Template Evaluation

**Primary domain:** Full-stack, local-first AI system — Java/Spring backend + Next.js PWA frontend, composed in a single monorepo.

**Decision:** No monolithic starter. Use two official generators + a hand-rolled monorepo skeleton.

| Layer | Generator | Why |
|---|---|---|
| Backend | Spring Initializr (`start.spring.io`) | Official; clean Spring Boot 4 project with exactly the chosen dependencies |
| Frontend | `create-next-app@latest` (Next.js 16) | Official; App Router + TS + Tailwind v4 + Turbopack; emits AGENTS.md/CLAUDE.md for AI-assisted coding |
| Repo | Hand-rolled monorepo skeleton | Holds `backend/` + `frontend/` + `docker-compose.yml` + planning docs |

**Rejected:** T3/RedwoodJS/Blitz (TS-everywhere — can't host a JVM agent runtime); single-layer starters (each covers half the system).

**Build tool:** Maven (confirmed by user) — simplest, ubiquitous IDE support. Java records replace Lombok. Spring Security deferred for MVP (custom PIN/biometric auth, FR-35).

**Monorepo layout:**
```
argus/
├── backend/            # Spring Boot 4 (Spring Initializr)
├── frontend/           # Next.js 16 (create-next-app)
├── docker-compose.yml  # Postgres 18 + Mongo 8.3 + Redis 8  (Ollama runs NATIVELY, not here)
├── .env.example        # committed
└── _bmad-output/ …     # planning docs (committed)
```

**Backend init (bootVersion pinned to latest 4.0.x at scaffold time):**
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d bootVersion=4.0.x -d packaging=jar \
  -d groupId=com.argus -d artifactId=argus-backend -d name=argus \
  -d dependencies=web,websocket,data-jpa,data-mongodb,data-redis,actuator,validation,postgresql \
  -o backend.zip
```

**Frontend init:**
```bash
npx create-next-app@latest frontend \
  --typescript --tailwind --eslint --app --src-dir --turbopack --import-alias="@/*"
```

**Decisions locked:** Java 25 + Maven + Spring Boot 4 (web/WebSocket/JPA/Mongo/Redis/Actuator/validation) backend; TypeScript + App Router + Tailwind v4 + Turbopack frontend.

**Note:** Running these two init commands is the *first implementation story*.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical (block implementation):** Model Resource Governance, Agent Orchestration Runtime, Data Architecture, Inter-Agent Event Bus.
**Important (shape architecture):** Auth & Security, API & Communication, Frontend Architecture, Infrastructure & Deployment.
**Deferred (post-MVP):** App-level column encryption, GitHub Actions CI, brokerage integration, QuantConnect backtesting.

### Decision 1 — Model Resource Governance (keystone) → Tiered + Model Gateway

On the 28GB Mac Mini, both models can co-reside (~26GB) but with too little headroom to be safe. Chosen approach:
- **Small model** (Gemma 4 E4B / Llama 3.2 3B, ~3GB) stays **always resident** (`keep_alive=-1`) → serves high-frequency Agents 1/2/3.
- **Big model** (Gemma 4 26B MoE, ~15GB) loaded **on-demand** with short `keep_alive` (~10–15m), **serialized through a backend Model Gateway** (queue/semaphore, concurrency=1) → serves Agents 4/5/6, Ask AI, Personas.
- Interactive latency mitigated by **token streaming** + **pre-warm** on user activity (open recommendation card / Ask AI panel).
- Ollama substrate: native load-on-demand, LRU eviction, request queuing (verified current behavior).
- The **Model Gateway** is the single home for: model selection, the budget-driven Claude Haiku fallback (FR-45), and the dev/prod model swap (the swappable LLM interface).
- **Rationale:** only approach that respects the RAM ceiling AND the mixed interactive/batch workload; pays for itself across several FRs.

### Decision 2 — Agent Orchestration Runtime (keystone) → Pure Java/Spring + Spring AI 2.0

Resolves open question #1. Agents are **Spring components on virtual threads (Loom)**, driven by `@Scheduled` + Redis events. All LLM calls (local Gemma + Claude Haiku) go through **Spring AI 2.0 `ChatClient`** (GA on Spring Boot 4 baseline), wrapped by the Model Gateway.
- **No Python layer.** Argus's agents are scheduled/event-driven data pipelines, not autonomous tool-using agents — heavyweight Python agent frameworks are over-fit.
- **Rationale:** single language (user's strength), one JVM, one deploy, RAM-light on the Mini; Spring AI gives provider-swap + Haiku fallback natively.

### Decision 3 — Data Architecture (keystone) → PostgreSQL 18 + Redis 8 (two stores)

Resolves open question #3 and **simplifies the PRD's original four-store plan** (drops MongoDB and the separate vector DB).
- **PostgreSQL 18** — relational (portfolio, positions, ACB lots, recommendations, calibration bins, trade journal, decision snapshots) + **JSONB** documents (news, social, reports, analysis payloads) + **pgvector 0.8.2** embeddings (semantic search, colocated).
- **Redis 8** — event bus + cache + WebSocket fan-out.
- Migrations via **Flyway**.
- **Rationale:** two services instead of four frees ~1.5–2GB RAM, simplifies backups (one `pg_dump` covers most of FR-40), enables structured+document+vector joins in one transaction. MongoDB's scale strengths are irrelevant at single-user volume.

### Decision 4 — Inter-Agent Event Bus → Redis Streams (agents) + pub/sub (UI)

- **Redis Streams** (persistent, consumer groups, ack, replay) for the agent event bus → a high-impact signal is never silently lost if a consumer is mid-load/busy (protects FR-14).
- Plain **Redis pub/sub** for the throwaway live-dashboard fan-out (dropped frames are harmless).

### Decision 5 — Authentication & Security

- **Server-side sessions in Redis** (not JWT) — instant revocation enables FR-39 remote session kill.
- PIN stored as **Argon2/BCrypt hash**; biometric via iOS **passkey/WebAuthn** unlocking the session.
- **Secrets at rest:** macOS **FileVault** (full-disk) + gitignored `.env`. App-level column encryption deferred — Tailscale-only + FileVault + single-user covers the threat model.
- Tailscale is the network boundary; platform never on the public internet.

### Decision 6 — API & Communication

- **REST (JSON)** request/response + **WebSocket (STOMP over Spring WebSocket)** for live push (prices, alerts, agent status).
- Errors via **RFC 9457 Problem Details** (native in Spring Boot 4).
- **springdoc-openapi** (Swagger UI) for personal API reference.

### Decision 7 — Frontend Architecture

- **TanStack Query** (server state) + **Zustand** (UI state); WebSocket updates feed the Query cache.
- **PWA** service worker for install + **Web Push** (FR-17); **TradingView Lightweight Charts**.

### Decision 8 — Infrastructure & Deployment

- **Docker Compose**: Postgres + Redis + backend + frontend. **Ollama native on host** (no Metal passthrough in Docker).
- **Backup service:** Spring `@Scheduled` — `pg_dump` every 6h + 15-min critical-data export to external SSD (FR-40).
- **Deploy:** `git pull` + `docker compose up` on the Mini. GitHub Actions (build/test on push) optional, deferred.

### Decision Impact Analysis

**Implementation sequence:** (1) scaffold backend+frontend → (2) Docker Compose data layer + Flyway schema → (3) Model Gateway + Spring AI wiring → (4) Redis Streams event bus → (5) Agent runtime (1, 7, then 5) → (6) WebSocket + dashboard → (7) PWA + Web Push → (8) auth + backup service.

**Cross-component dependencies:** Model Gateway underpins all agents + Ask AI + Personas; Redis Streams underpins agent coordination + Agent 5 triggering; PostgreSQL underpins portfolio, calibration, and vector search; session auth depends on Redis.

**PRD reconciliation needed:** Data Architecture change (Postgres+Redis only) must be reflected in PRD §10 Technology Constraints. *(Done.)*

## Implementation Patterns & Consistency Rules

These rules exist to prevent multiple AI agents (or future sessions) from writing the codebase inconsistently. They are mandatory.

### Naming Patterns

**The case boundary (the key rule):** `snake_case` in the database; `camelCase` from Java outward.
- **PostgreSQL:** `snake_case`; plural tables (`recommendations`, `news_articles`); columns `created_at`; FK `<entity>_id`; indexes `idx_<table>_<cols>`. Flyway files `V<n>__description.sql`.
- **Java / JSON / TypeScript:** `camelCase` fields throughout. JPA maps snake↔camel; Spring emits JSON camelCase; TS consumes directly.
- **REST:** plural, kebab-case paths (`/api/portfolio-positions`); `{id}` path params; **no version prefix** for MVP (addable later).
- **Java:** `PascalCase` classes; packages `com.argus.<domain>`. **TS/React:** `PascalCase.tsx` components; `useXxx` hooks; `camelCase` utilities.

### Structure Patterns (feature/domain-based, not layer-based)

- **Backend:** `com.argus.{portfolio, agent, recommendation, intelligence, model, common}` — each owns its controller/service/repository/domain. Tests mirror packages under `src/test/java`.
- **Frontend:** `src/features/{portfolio, recommendations, agents, …}`; shared `src/components`, `src/lib`, `src/hooks`; tests co-located `*.test.tsx`.

### Format Patterns

- **Success responses:** return the resource directly (no envelope).
- **Errors:** RFC 9457 Problem Details — one error shape everywhere.
- **Dates:** ISO-8601 **UTC** in JSON; `timestamptz` in Postgres; render in **America/Toronto** in the UI (8am briefing = user-local).
- **Money:** `NUMERIC`/`BigDecimal`, never floats; always carry currency + CAD/USD.
- **JSON fields:** camelCase; booleans true/false; nulls explicit (`Optional` in Java, nullable types in TS).

### Communication Patterns

- **Event names (Redis Streams):** dot-notation, past tense — `news.detected`, `signal.high_impact`, `recommendation.created`, `agent.run.completed`.
- **Event envelope:** `{ eventId, type, occurredAt (UTC), version, payload }`; one consumer group per agent.
- **Frontend state:** TanStack Query keys as arrays (`['recommendation', id]`); Zustand slices per domain; immutable updates only.

### Process Patterns

- **Error handling:** backend `@RestControllerAdvice` → Problem Details; agents catch → log → retry with exponential backoff (Resilience4j for external rate limits) → emit error event → never crash the runtime.
- **Loading:** TanStack Query `isLoading`/`isFetching` + skeleton UI.
- **Logging:** structured JSON (SLF4J/Logback) with `timestamp, level, component, event, durationMs, cost` → feeds the System Ops Dashboard.

### Enforcement Guidelines

**All AI agents / contributors MUST:**
- Use the snake_case↔camelCase boundary exactly as specified — no camelCase columns, no snake_case JSON.
- Place code in the correct feature package/folder; never create layer-first top-level dirs.
- Return Problem Details for every error; never invent ad-hoc error shapes.
- Emit money as BigDecimal/NUMERIC and timestamps as UTC ISO-8601.
- Route every LLM call through the Model Gateway; never call Ollama/Anthropic directly.
- Publish agent events through Redis Streams with the standard envelope.

**Anti-patterns to avoid:** camelCase DB columns; response envelopes around success payloads; float money; local-time timestamps in storage; direct model calls bypassing the Gateway; fire-and-forget pub/sub for signals that must not be lost.

## Project Structure & Boundaries

**Deployment shape:** single-process **modular monolith** (one Spring Boot app + one Next.js app). Microservices were rejected — multiple JVMs/processes would multiply RAM overhead, fatal on a 28GB single-user box. The `agent` and `model` packages are kept clean-bounded so the agent runtime *could* later be extracted into its own process with minimal surgery, but that is explicitly not done now.

### Complete Monorepo Tree
```
argus/
├── README.md
├── .gitignore                      # committed
├── .env.example                    # committed
├── docker-compose.yml              # postgres, redis, backend, frontend  (Ollama runs NATIVELY on host)
│
├── backend/                        # Spring Boot 4 · Java 25 · Maven
│   ├── pom.xml
│   ├── src/main/java/com/argus/
│   │   ├── ArgusApplication.java
│   │   ├── config/                 # Spring, WebSocket/STOMP, Redis, datasource, Spring AI
│   │   ├── common/                 # error (RFC 9457 advice), event envelope, logging, utils
│   │   ├── model/                  # ★ Model Gateway: Spring AI ChatClient wrap, queue/semaphore,
│   │   │                           #   keep-alive policy, pre-warm, Haiku fallback, dev/prod profiles
│   │   ├── agent/                  # ★ agent runtime: scheduler, virtual-thread executor,
│   │   │                           #   Redis Streams producer/consumer, lifecycle, base Agent
│   │   ├── marketdata/             # Finnhub WS prices, FX, VIX ingestion
│   │   ├── portfolio/              # F1/F2: positions, ACB lots, corporate actions, health score
│   │   ├── intelligence/           # Agent 1 (news) + source-credibility engine  (Agents 2/3/4 later)
│   │   ├── calendar/               # Agent 7: economic/earnings calendar, quiet period
│   │   ├── recommendation/         # ★ Agent 5: graduation state machine, probability SCORING engine
│   │   │                           #   (model-derived, not LLM), diagnostic report, sizing, calibration
│   │   ├── persona/                # 4 MVP personas (local model)
│   │   ├── conversation/           # Ask AI (context assembly → Model Gateway)
│   │   ├── notification/           # Web Push, alert-fatigue gate, urgency tiers, morning briefing
│   │   ├── cost/                   # Cost Governor / budget thresholds + auto-switch
│   │   ├── ops/                    # System Ops metrics, backup @Scheduled job
│   │   └── security/               # PIN/Argon2, Redis session, panic mode, session-kill
│   │   └── resources/
│   │       ├── application.yml / application-dev.yml / application-prod.yml
│   │       └── db/migration/       # Flyway V<n>__*.sql
│   └── src/test/java/com/argus/…   # mirrors packages
│
├── frontend/                       # Next.js 16 · TS · Tailwind v4 · App Router
│   ├── package.json · next.config.ts · tsconfig.json
│   ├── public/                     # PWA manifest, icons, service-worker (Web Push)
│   └── src/
│       ├── app/                    # routes: (dashboard) home, portfolio, intelligence, agents, profile
│       ├── features/               # portfolio, recommendations, agents, intelligence, ops, conversation, personas
│       ├── components/ui/          # shadcn/ui + shared
│       ├── lib/                    # apiClient, wsClient (STOMP), queryClient, utils
│       ├── hooks/                  # useXxx
│       └── stores/                 # Zustand slices
│
└── _bmad-output/                   # planning docs (committed)
```
★ = load-bearing components (Model Gateway, agent runtime, Agent 5).

### Architectural Boundaries
- **API boundary:** frontend ↔ backend only via REST (`/api/...`) + STOMP WebSocket. Frontend never touches the DB or models directly.
- **Model boundary:** all LLM calls (agents, Ask AI, personas) flow through `com.argus.model` (Model Gateway). Nothing calls Ollama/Anthropic directly.
- **Agent boundary:** agents never call each other directly — they publish/consume via Redis Streams (`com.argus.agent`). Agent 5 subscribes to high-impact signals.
- **Data boundary:** only each feature's repositories touch Postgres; Redis access via a thin cache/stream service.

### Requirements → Structure Mapping (MVP)
| Feature (PRD) | Backend package | Frontend feature |
|---|---|---|
| F1/F2 Portfolio, Health, ACB, corp-actions | `portfolio`, `marketdata` | `features/portfolio` |
| F3 Agent 1 News + credibility | `intelligence`, `agent` | `features/intelligence` |
| F4 Agent 5 Recommendations + calibration | `recommendation`, `model` | `features/recommendations` |
| F5 Briefing + Push | `notification` | `app/(dashboard)` |
| F7 Agent 7 Calendar | `calendar`, `agent` | `features/intelligence` |
| F8/F9 Agent + Ops dashboards | `ops`, `agent` | `features/agents`, `features/ops` |
| F10 Ask AI | `conversation`, `model` | `features/conversation` |
| F11 Personas | `persona`, `model` | `features/personas` |
| F12 Security | `security` | `features/profile` |
| F13 Backup / F15 Cost | `ops`, `cost` | `features/ops` |

### Data Flow (news → notification, FR-14)
`marketdata`/`intelligence` (Agent 1) → Redis Stream `signal.high_impact` → `agent` runtime wakes `recommendation` (Agent 5) → Model Gateway (big model) → scoring engine produces probability → persisted in Postgres → `notification` alert-fatigue gate → Web Push → PWA; live updates pushed over STOMP.

### Development Workflow
- **Dev (laptop M5/16GB):** `docker compose up` (Postgres+Redis), backend via `mvn spring-boot:run` (profile `dev` → small model/mock), frontend `npm run dev`. Ollama optional with a small model.
- **Build:** Maven builds the backend jar; Next.js builds via Turbopack.
- **Deploy (Mini M3/28GB):** `git pull` → `docker compose up` → Ollama native (profile `prod` → Gemma 4 26B MoE).

## Architecture Validation Results

### Coherence Validation ✅
All technology choices are mutually compatible and version-verified (June 2026): Java 25 + Spring Boot 4 + Spring AI 2.0 (GA on Boot 4 baseline), PostgreSQL 18 + pgvector 0.8.2, Next.js 16 + TanStack Query/Zustand, Redis 8 Streams. Implementation patterns align with the stack; the single-process modular monolith fits the 28GB box. No contradictory decisions.

### Requirements Coverage Validation ✅
All 15 MVP features (F1–F15) map to a backend package and a frontend home (see Requirements → Structure Mapping). NFRs each have an owner: security → `security` + Tailscale + FileVault; observability → structured logging + `ops`; cost → `cost`; reliability → agent retry/error-event + Degraded Mode.

### Gap Analysis (recorded; each validated against runnable code on the Mac Mini)

**Important (handle early; not blocking):**
- **GAP-1 — 28GB headroom is mitigated, not proven.** Steady-state ~26GB / ~2GB margin. Model Gateway policy should hold but worst-case is unverified on hardware. **#1 project risk.** Validation: RAM + latency spike on the Mini is the FIRST implementation task after scaffold.
- **GAP-2 — Ask-AI ≤15s latency is an assumption.** Same root as GAP-1; closed by the same spike (measure Gemma 4 26B MoE first-token latency on M3).
- **GAP-3 — Degraded Mode (FR-44) has no owner.** Add a platform-mode coordinator in `agent`/`ops` owning Normal/Degraded state and gating agent execution.

**Minor (noted):**
- **GAP-4 — Finnhub single-point-of-failure / free-tier caps.** Mitigate with Resilience4j rate-limiting; documented fallback (Alpha Vantage/Yahoo) as known risk.
- **GAP-5 — Redis not backed up (by design).** Streams carry agent events; a wipe loses in-flight signals. Acceptable for MVP (portfolio data is the protected asset).
- **GAP-6 — Probability scoring algorithm undefined.** Design-time detail of the `recommendation` epic; flagged as a known unknown that calibration depends on.

### Architecture Completeness Checklist
**Requirements Analysis** — [x] context analyzed · [x] scale/complexity assessed · [x] constraints identified · [x] cross-cutting concerns mapped
**Architectural Decisions** — [x] critical decisions documented with versions · [x] tech stack specified · [x] integration patterns defined · [x] performance considerations addressed *(designed; empirical validation pending GAP-1/2 spike)*
**Implementation Patterns** — [x] naming · [x] structure · [x] communication · [x] process
**Project Structure** — [x] directory structure · [x] component boundaries · [x] integration points · [x] requirements→structure mapping

### Architecture Readiness Assessment
**Overall Status: READY WITH MINOR GAPS.** Deliberately not "fully ready" — the only distance to that label is empirical hardware validation (GAP-1/2), which cannot be performed until runnable code exists on the Mini, and is therefore the first implementation task rather than an architecture blocker. Per user direction, every gap is recorded and will be validated against runnable code on the Mac Mini.

**Confidence:** High on design coherence; medium specifically on 28GB headroom until benchmarked.

**Key strengths:** ruthless fit to the 28GB/$100 constraints; Gemma 4 26B MoE choice; honest model-derived-probability + calibration design; clean agent/model boundaries that keep future extraction cheap.

**Areas for future enhancement:** post-MVP extraction of the agent runtime into its own process (if ever needed); richer Finnhub fallbacks; app-level encryption at rest.

### Implementation Handoff
**AI agent guidelines:** follow all decisions exactly; route every LLM call through the Model Gateway; agents communicate only via Redis Streams; respect the snake_case↔camelCase boundary and feature-package structure; return RFC 9457 errors.

**First implementation priorities (in order):**
1. Scaffold backend (Spring Initializr, Maven, Boot 4) + frontend (`create-next-app@latest`) into the monorepo.
2. **RAM + latency validation spike on the Mac Mini (GAP-1/2)** — load Gemma 4 26B MoE via Ollama, measure resident RAM under worst case and Ask-AI first-token latency; confirm or revise the model/keep-alive policy before building further.
3. Docker Compose data layer + Flyway baseline schema.
4. Model Gateway + Spring AI wiring (with dev/prod profiles).
