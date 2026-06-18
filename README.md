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
├── .env.example        # copy to .env (gitignored) and fill in keys
├── _bmad-output/       # planning + implementation artifacts (PRD, architecture, epics, stories)
└── README.md
```

The data layer (PostgreSQL 18 + pgvector, Redis 8) and `docker-compose.yml` arrive in **Story 1.2**.
Ollama runs **natively** on the host (not in Docker) — it is not part of compose.

## Stack

| Layer    | Choice |
|----------|--------|
| Backend  | Java 25 · Maven · **Spring Boot 4.0.7** (Web MVC, WebSocket, Data JPA, Data Redis, Actuator, Validation, PostgreSQL driver) |
| Frontend | **Next.js 16** · TypeScript · Tailwind v4 · Turbopack · App Router |
| Data     | PostgreSQL 18 (relational + JSONB + pgvector) · Redis 8 — *wired in Story 1.2* |
| AI       | Ollama (small model always-resident + Gemma 4 26B MoE on demand) · Claude Haiku fallback — *later epics* |

## Prerequisites

- JDK 25, Maven (or use the bundled `./mvnw` wrapper)
- Node.js 20.9+ and npm (Next.js 16 floor; developed on Node 25 / npm 11)

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

### Backend

```bash
cd backend
./mvnw spring-boot:run        # starts on http://localhost:8080
```

Health check: <http://localhost:8080/actuator/health> → `{"status":"UP"}`.

Run tests: `./mvnw test`

> During Story 1.1 the backend boots **without** a database: `DataSourceAutoConfiguration` is excluded and
> the Redis health indicator is disabled in `application.yml`. Story 1.2 reverses this and stands up
> Postgres + Redis via Docker Compose.

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

## Planning & implementation docs

See `_bmad-output/planning-artifacts/` (PRD, architecture, epics) and
`_bmad-output/implementation-artifacts/` (sprint status + per-story specs).