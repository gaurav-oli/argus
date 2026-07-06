# Argus

AI-powered personal investment intelligence platform — a local-first, single-process modular monolith
(Spring Boot backend + Next.js PWA frontend) that runs 24/7 on a Mac Mini and is reachable from your
devices over Tailscale.

> Argus is a **decision-support & discipline tool, not an alpha-generation engine.** Its value is
> behavioral: stay informed without being overwhelmed, confront the bear case, size positions sensibly,
> and avoid impulsive biased decisions. Recommendation probabilities are **model-derived** (a rule/weight
> engine), never produced free-hand by an LLM.

## Repository layout

```
argus/  (this repo)
├── backend/            # Spring Boot 4 · Java 25 · Maven  — API, agents, Model Gateway
├── frontend/           # Next.js 16 · TypeScript · Tailwind v4 · App Router · PWA
├── docker-compose.yml  # local data layer: Postgres 18 (pgvector) + Redis 8
├── .env.example        # copy to .env (gitignored) and fill in keys
├── _bmad-output/       # planning + implementation artifacts (PRD, architecture, epics, stories)
└── README.md
```

In dev, `docker compose up` runs **Postgres + Redis only**; the backend runs via `mvn spring-boot:run`
and the frontend via `npm run dev`. Ollama runs **natively** on the host (not in Docker). For the
Mac Mini deploy, the full stack (backend + frontend containers) comes up behind the `deploy` profile —
`docker compose --profile deploy up -d --build` — reachable from your devices over Tailscale.
See the **[deploy & Tailscale runbook](docs/deploy-runbook.md)** for the full procedure.

## Stack

| Layer    | Choice |
|----------|--------|
| Backend  | Java 25 · Maven · **Spring Boot 4.0.7** (Web MVC, WebSocket, Data JPA, Data Redis, Actuator, Validation, PostgreSQL driver) |
| Frontend | **Next.js 16** · TypeScript · Tailwind v4 · Turbopack · App Router |
| Data     | PostgreSQL 18 (relational + JSONB + pgvector 0.8.2) · Redis 8 · Flyway migrations |
| AI       | Ollama (small model always-resident + Gemma 4 26B MoE on demand) · Claude Haiku fallback — *later epics* |

## Prerequisites

- JDK 25, Maven (or use the bundled `./mvnw` wrapper)
- Node.js 20.9+ and npm (Next.js 16 floor; developed on Node 25 / npm 11)
- Docker + Docker Compose — required to run the data layer **and** to run the backend tests
  (they use Testcontainers, which needs a running Docker daemon)

## How this monorepo was scaffolded (Story 1.1)

Backend — generated via Spring Initializr (Maven, Java 25, Spring Boot 4.0.7), then restructured so the
base package is `com.argus`:

```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d bootVersion=4.0.7 -d packaging=jar \
  -d groupId=com.argus -d artifactId=argus-backend -d name=argus \
  -d dependencies=web,websocket,data-jpa,data-redis,actuator,validation,postgresql \
  -o backend.zip
unzip backend.zip -d backend && rm backend.zip
```

> **Version note:** Spring Initializr may hand back a legacy `.RELEASE`-suffixed
> id (e.g. `4.0.7.RELEASE`) that does **not** exist in Maven Central. If the
> first build fails with `Non-resolvable parent POM`, set the `pom.xml` parent
> `<version>` to the plain `4.0.7` (confirm the latest `4.0.x` via Maven
> Central's `maven-metadata.xml`). The committed `pom.xml` is already corrected.

Frontend — generated via `create-next-app`:

```bash
npx create-next-app@latest frontend \
  --typescript --tailwind --eslint --app --src-dir --turbopack --import-alias="@/*"
```

> Note: MongoDB is intentionally **not** a dependency — the architecture uses PostgreSQL + Redis only.

## Running locally

First time only: `cp .env.example .env` and fill in what you have (blank values degrade gracefully).
The defaults already work for local development.

### Data layer (start this first)

```bash
docker compose up -d          # Postgres 18 (pgvector) + Redis 8
docker compose ps             # both should report "healthy"
```

### Backend

```bash
cd backend
./mvnw spring-boot:run        # starts on http://localhost:8080
```

The backend connects to Postgres + Redis on startup and Flyway applies the baseline migration, so the
data layer must be up first. Health check: <http://localhost:8080/actuator/health> → `{"status":"UP"}`
(with `db` and `redis` indicators UP).

Run tests: `./mvnw test` — **requires a running Docker daemon** (Testcontainers spins up throwaway
Postgres + Redis; the whole suite, including the context-load test, depends on it).

### Frontend

```bash
cd frontend
npm install                   # first time
npm run dev                   # starts on http://localhost:3000
```

## Backend package structure (feature/domain-based)

`com.argus.<domain>` — each package owns its own controller/service/repository/domain:

`config · common · model · agent · marketdata · portfolio · intelligence · calendar · recommendation ·
persona · conversation · notification · cost · ops · security`

## Analyst/Investor loop & validation knobs

Agent 5 runs a self-improving loop with **no user input**: the *Analyst* produces a recommendation, the
*Investor* opens a fixed-notional **paper trade** ($100, pretend money — never your real portfolio) at
the live price, marks it to market at the horizon, and the realized win/loss feeds back as per-agent
signal-weight multipliers and isotonic probability calibration (Phase B adaptive tuning). Everything is
deterministic (no LLM numbers) and reversible; the pure scoring engine is never rewritten.

Production defaults judge trades on a real investing timeframe and resist noise. For validation you can
temporarily lower them from `.env` (no rebuild — recreate the backend with `docker compose --profile
deploy up -d`), then **remove the overrides to return to production**:

| `.env` override | Prod default | Validation | Effect |
|---|---|---|---|
| `PAPER_INVESTOR_HORIZON_DAYS` | 30 | e.g. 2 | how long a paper trade is held before it's marked to market (new trades only) |
| `ADAPTIVE_TUNING_MIN_SAMPLE` | 10 | e.g. 2 | closed-trade floor below which an agent's weight multiplier stays 1.0 |
| `ADAPTIVE_TUNING_RECOMPUTE_ON_BOOT` | false | true | also run the tuning recompute at startup instead of only nightly (02:30) |

Ops: `POST /api/recommendations/tuning/recompute` (session-gated) forces a recompute on demand and
returns the resulting per-agent reliability. **Cleanup after validation:** remove the three overrides
above and recreate the backend; if you seeded synthetic `simulated_trades` by hand, delete those rows
plus the derived `paper_trades` / `agent_reliability` / `probability_calibration` and restart so the
in-memory tuning cache resets (otherwise multipliers derived from test data linger).

## Planning & implementation docs

See `_bmad-output/planning-artifacts/` (PRD, architecture, epics) and
`_bmad-output/implementation-artifacts/` (sprint status + per-story specs).