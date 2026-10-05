# Argus

AI-powered investment intelligence platform. It is a local-first, single-process modular monolith
(Spring Boot backend + Next.js PWA frontend) that runs 24/7 on a Mac Mini.

**Access:** invite-only multi-user (October 2026). The app is published over HTTPS with Tailscale
Funnel. People sign in with Google, but only if the admin has invited their email. Each person's
portfolio, briefing and investor profile are private. The market intelligence the agents produce
(news, recommendations, analysis) is shared. See **[docs/multi-user.md](docs/multi-user.md)**.

> Argus is a **decision-support & discipline tool, not an alpha-generation engine.** Its value is
> behavioral: stay informed without being overwhelmed, confront the bear case, size positions sensibly,
> and avoid impulsive biased decisions. Recommendation probabilities are **model-derived** (a rule/weight
> engine), never produced free-hand by an LLM.

## Repository layout

```
argus/  (this repo)
├── backend/            # Spring Boot 4 · Java 25 · Maven  — API, agents, Model Gateway
├── frontend/           # Next.js 16 · TypeScript · Tailwind v4 · App Router · PWA
├── docker-compose.yml  # Postgres 18 (pgvector) + Redis 8; backend + frontend under the `deploy` profile
├── .env.example        # copy to .env (gitignored) and fill in keys
├── scripts/            # backup.sh + launchd installer (Story 10.1)
├── docs/               # runbooks, multi-user design, Mini validation checklist
├── _bmad-output/       # planning + implementation artifacts (PRD, architecture, epics, stories)
├── RECOVERY.md         # restore / restart runbook
└── README.md
```

In dev, `docker compose up` runs **Postgres + Redis only**; the backend runs via `mvn spring-boot:run`
and the frontend via `npm run dev`. Ollama runs **natively** on the host (not in Docker). For the
Mac Mini deploy, the full stack (backend + frontend containers) comes up behind the `deploy` profile
(`docker compose --profile deploy up -d --build`) and is exposed through Tailscale Funnel.
See the **[deploy & Tailscale runbook](docs/deploy-runbook.md)** for the full procedure.

## Stack

| Layer    | Choice |
|----------|--------|
| Backend  | Java 25 · Maven · **Spring Boot 4.1.1** (Web MVC, WebSocket/STOMP, Data JPA, Data Redis, Mail, Actuator, Validation) · Spring AI 2.0.1 · ta4j · Commons Math · PDFBox · resilience4j · web-push |
| Frontend | **Next.js 16.3** · React 19.3 · TypeScript · Tailwind v4 · App Router · PWA · `motion` · lightweight-charts · recharts |
| Data     | PostgreSQL 18 (relational + JSONB + pgvector 0.8.2) · Redis 8 (sessions, agent streams, dedup) · Flyway (V1–V75) |
| AI       | Ollama on the host: `llama3.2:3b` (SMALL tier), Gemma 4 (BIG tier, one call at a time, Haiku fallback on timeout/error/blank) · Claude Haiku 4.5 for escalations, under a monthly budget cap (`ARGUS_BUDGET_MONTHLY_USD`, auto-switch to local at 95%) |
| Auth     | Google Sign-In (hand-rolled OAuth) + invite allowlist · Redis-backed server sessions (`ARGUS_SESSION` cookie) |

## What it does: the agents

Agents run on schedules inside the one backend process (`com.argus.*`). Probabilities are
deterministic. LLMs write prose and verdicts, never the numbers.

| # | Agent | What it does |
|---|---|---|
| 1 | News Intelligence | Finnhub / GDELT / RSS ingestion, sentiment (small model), source credibility, Stranger Danger, breaking alerts |
| 2 | Social | StockTwits + Reddit chatter |
| 3 | Internet | Hacker News + Wikipedia attention |
| 4 | SEC insider | EDGAR Form 4 insider activity |
| 5 | Recommender | Probability scoring engine, graduation (shadow/probation/frozen), paper investor, adaptive tuning, logic review, Haiku "debate this call" |
| 6 | Cost Governor | Haiku spend tracking, 70/80/95% budget alerts, auto-switch to local |
| 7 | Economic Calendar | Earnings / IPO / Fed calendar, pre-event alerts, quiet periods |
| 8 | Macro | Macro/political relevance tagging, weekly keyword learning |
| 9 | On-demand Research | Plans and gathers across agents; Haiku writes the final synthesis |
| 10 | Chart Reader | Daily candles (Alpha Vantage / Yahoo), ta4j indicators, chart studies |
| 11 | Deep Analyst | Nightly multi-stage analysis; Haiku verdict tracked against the local model; thesis tracker + scorecard |
| 12 | Fundamentals | Finnhub ratios + valuation (CHEAP/FAIR/RICH) |
| 13 | Trade Learner | Lessons and learned rules from paper-trade wins and losses |
| 14 | Filings Reader | 10-Q/10-K/earnings digests with fact verification |
| 15 | Academic Strategies | Published cross-sectional signals, back-tested on Argus's own data |

Around the agents:
- **Portfolio:** PDF statement import from any broker. Import is adaptive and self-healing: a
  deterministic parse is retried with the local model, then Haiku, then flagged for review if still
  incomplete. Also ACB with purchase-time FX, a live value, a health score, and a long-term outlook
  per holding.
- **Ask-AI:** chat with personas (Buffett, Lynch, Devil's Advocate, Canadian lens).
- **Morning briefing** and Web Push alerts with alert discipline (tiers, fatigue gate, dedup).
- **Ops dashboards:** the Agents page (fleet status, accuracy, calibration, budget, freshness, backup,
  hardware).

Pages:
- `/` Home
- `/portfolio`
- `/intelligence`
- `/agents`
- `/profile` (settings; admins also get **People on Argus** here)

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
The defaults already work for local development. Sign-in needs a Google OAuth client
(`ARGUS_GOOGLE_OAUTH_*`) even locally. With the `dev` profile, the model is a mock and demo data is
seeded.

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

Run tests: `./mvnw test`. This **requires a running Docker daemon**: Testcontainers spins up
throwaway Postgres + Redis, and the whole suite (about 1,000 tests), including the context-load test,
depends on it.

### Frontend

```bash
cd frontend
npm install                   # first time
npm run dev                   # starts on http://localhost:3000
npm run lint                  # eslint
npx tsc --noEmit              # typecheck (no script; there are no frontend unit tests)
```

## Backend package structure (feature/domain-based)

`com.argus.<domain>` — each package owns its own controller/service/repository/domain:

`agent · backup · briefing · calendar · common · config · conversation · cost · deepanalysis · email ·
filings · fundamentals · intelligence · internet · learning · marketdata · model · notification · ops ·
persona · portfolio · push · recommendation · regime · research · resilience · sec · security · social ·
strategy · technical · watchlist`

## Analyst/Investor loop & validation knobs

Agent 5 runs a self-improving loop with **no user input**: the *Analyst* produces a recommendation, the
*Investor* opens one fixed-notional **paper trade** ($100, pretend money — never your real portfolio)
per horizon (7/30/90 days) at the live price — but only when that (ticker, direction, horizon) thesis
isn't already open; a repeat recommendation **re-affirms** the open legs instead of duplicating them.
At each horizon the leg is marked to market and wins on its **direction-adjusted return in excess of
SPY** (captured at entry/exit; absolute return when unbenchmarked) — so the loop measures signal
quality, not market beta. Realized outcomes feed back as per-agent signal-weight multipliers (hit rate
weighted by each signal's weight) and isotonic probability calibration (Phase B adaptive tuning), with
a Brier score surfaced on the Agents page. Everything is deterministic (no LLM numbers) and reversible;
the pure scoring engine is never rewritten.

Production defaults judge trades on a real investing timeframe and resist noise. For validation you can
temporarily lower them from `.env` (no rebuild — recreate the backend with `docker compose --profile
deploy up -d`), then **remove the overrides to return to production**:

| `.env` override | Prod default | Validation | Effect |
|---|---|---|---|
| `PAPER_INVESTOR_HORIZON_DAYS_LIST` | 7,30,90 | e.g. 1,30,90 | the staggered horizons; one leg per horizon per thesis |
| `PAPER_INVESTOR_HORIZON_DAYS` | 0 (off) | e.g. 2 | legacy single-horizon knob — when >0 it replaces the list entirely |
| `ADAPTIVE_TUNING_MIN_SAMPLE` | 10 | e.g. 2 | closed-trade floor below which an agent's weight multiplier stays 1.0 |
| `ADAPTIVE_TUNING_RECOMPUTE_ON_BOOT` | false | true | also run the tuning recompute at startup instead of only nightly (02:30) |

Ops: `POST /api/recommendations/tuning/recompute` (session-gated) forces a recompute on demand and
returns the resulting per-agent reliability. **Cleanup after validation:** remove the three overrides
above and recreate the backend; if you seeded synthetic `simulated_trades` by hand, delete those rows
plus the derived `paper_trades` / `agent_reliability` / `probability_calibration` and restart so the
in-memory tuning cache resets (otherwise multipliers derived from test data linger).

## Docs

| Doc | What it covers |
|---|---|
| [docs/multi-user.md](docs/multi-user.md) | Google Sign-In, invites, private vs shared data, admin, **known security gaps** |
| [docs/deploy-runbook.md](docs/deploy-runbook.md) | Mac Mini deploy, Tailscale Funnel, OAuth + Gmail setup |
| [RECOVERY.md](RECOVERY.md) | Restore from backup; restart after a reboot |
| [docs/mac-mini-validation.md](docs/mac-mini-validation.md) | Hardware-only validation checklist (dated history) |
| [docs/backup-build-checklist.md](docs/backup-build-checklist.md) | Backup build notes |
| `_bmad-output/planning-artifacts/` | PRD, architecture, epics. These are written for the original single-user design, so treat them as historical where they conflict with the code. |
| `_bmad-output/implementation-artifacts/` | Sprint status + per-story specs |