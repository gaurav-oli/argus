---
baseline_commit: 9788bdea32b3a7eaa3492ed06529e30fbb06cc12
---

# Story 1.2: Stand up the data layer

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the builder,
I want Postgres 18 (+pgvector) and Redis 8 running via Docker Compose with a Flyway baseline,
so that the app has persistence.

## Acceptance Criteria

1. **Given** a `docker-compose.yml` at the repo root, **When** I run `docker compose up -d`, **Then** a **PostgreSQL 18** service (with pgvector) and a **Redis 8** service start and report healthy (compose healthchecks pass).
2. **Given** Docker Compose is up, **When** the backend starts (`./mvnw spring-boot:run`), **Then** it connects to Postgres **and** Redis with no startup error (the temporary Story-1.1 datasource/redis deferral is removed).
3. **Given** the backend starts against an empty database, **When** Flyway runs, **Then** it applies a **baseline migration** (`V1__*.sql`) and records it in `flyway_schema_history`.
4. **Given** the baseline migration has run, **Then** the **pgvector extension is available** (`SELECT extname FROM pg_extension WHERE extname = 'vector'` returns a row).
5. **Given** the data layer is wired, **When** I hit `GET /actuator/health`, **Then** it returns `{"status":"UP"}` with the **db and redis health indicators both UP** (the Story-1.1 `management.health.redis.enabled: false` override is removed).
6. **Given** the test suite, **When** I run `./mvnw test` with Docker running, **Then** an integration test proves context load + DB/Redis connectivity + Flyway baseline + pgvector presence (via Testcontainers — no dependency on a manually-started compose stack).

## Tasks / Subtasks

- [x] Task 1 — Author `docker-compose.yml` at repo root (AC: #1)
  - [x] `postgres` service `pgvector/pgvector:0.8.2-pg18`, env from `.env`, port `5432`, healthcheck `pg_isready`. **Volume mounted at `/var/lib/postgresql`** (NOT `/data` — PG18 restart-loops otherwise; see Debug Log).
  - [x] `redis` service `redis:8`, port `6379`, volume `argus-redisdata:/data`, healthcheck `redis-cli ping`.
  - [x] Two named volumes declared. No backend/frontend services (deferred to Story 1.8). `docker compose up -d` → both report healthy.
- [x] Task 2 — Clean up `.env.example` + create `.env` (AC: #1, #2)
  - [x] Removed obsolete `MONGO_*` lines; added `POSTGRES_DB=argus`; updated comment.
  - [x] Created gitignored local `.env` (`POSTGRES_PASSWORD=argus`, matching the app's local default).
- [x] Task 3 — Add Flyway to the backend (AC: #3)
  - [x] Added **`org.springframework.boot:spring-boot-flyway`** (the Boot-4 autoconfig module; `flyway-core` alone has no autoconfig — see Debug Log) + `org.flywaydb:flyway-database-postgresql`. Versions parent-managed.
  - [x] Created `backend/src/main/resources/db/migration/V1__baseline.sql` (`CREATE EXTENSION IF NOT EXISTS vector;`).
- [x] Task 4 — Re-wire `application.yml` (REVERT the Story-1.1 deferral) (AC: #2, #5)
  - [x] Removed the `DataSourceAutoConfiguration` exclude block.
  - [x] Removed the `management.health.redis.enabled: false` override.
  - [x] Added `spring.datasource` (env placeholders w/ local defaults), `spring.data.redis`, `spring.jpa.hibernate.ddl-auto: validate`, `open-in-view: false`, `spring.flyway.enabled: true`. Kept actuator exposure.
- [x] Task 5 — Integration test via Testcontainers (AC: #6)
  - [x] Added `spring-boot-testcontainers`, **`org.testcontainers:testcontainers-junit-jupiter`**, **`org.testcontainers:testcontainers-postgresql`** (test) — note the Testcontainers-2.x `testcontainers-*` artifact names (Boot 4 ships TC 2.0.5; see Debug Log). Redis via core `GenericContainer`.
  - [x] Added `TestcontainersConfiguration` (`@ServiceConnection` Postgres pgvector image + `@ServiceConnection(name="redis")` GenericContainer).
  - [x] `ArgusApplicationTests` imports it; added `DataLayerIntegrationTest` asserting Flyway `V1`, pgvector present, Redis PING. `./mvnw test` → 4/4 green.
- [x] Task 6 — Verify end-to-end (AC: #1–#5)
  - [x] `docker compose up -d` both healthy; `./mvnw spring-boot:run` connected to pg+redis; logs show Flyway applying `V1`.
  - [x] `/actuator/health` (details exposed via runtime arg) → `db: UP` (PostgreSQL) + `redis: UP` (8.8.0), overall UP. Reverted to default config (no file change for the verify).
  - [x] `./mvnw test` green with Docker running (Testcontainers).

## Dev Notes

### CRITICAL — this story REVERTS a temporary hack from Story 1.1
Story 1.1 made the backend boot **without** a database by (a) excluding `DataSourceAutoConfiguration` and (b) disabling the Redis health indicator in `backend/src/main/resources/application.yml`. **Both were explicitly flagged as "Story 1.2 MUST remove this."** Removing them is Task 4 and is required for AC #2 and AC #5. Do not leave either in place. [Source: 1-1-scaffold-the-monorepo.md Dev Notes "App-startup caveat"; deferred-work.md]

Current `application.yml` (the lines to delete):
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration   # DELETE
management:
  health:
    redis:
      enabled: false   # DELETE (and the surrounding health: block if now empty)
```

### Stack & versions (locked / verified 2026-06-18)
- **PostgreSQL 18 + pgvector**: Docker image `pgvector/pgvector:0.8.2-pg18` (verified present on Docker Hub; pgvector **0.8.2** is the architecture-pinned version; `0.8.3-pg18` also exists if a bump is ever wanted). [Source: architecture.md#Decision 3 — "pgvector 0.8.2"]
- **Redis 8**: image `redis:8` (latest 8.x verified on Docker Hub). [Source: architecture.md#Decision 3]
- **Flyway**: `flyway-core` + `flyway-database-postgresql` — Flyway 10+ split Postgres support into its own module; both are required or Flyway won't recognize the DB. Versions are managed by the Spring Boot 4.0.7 parent; **do not pin**. [Source: architecture.md#Decision 3 — "Migrations via Flyway"]
- **Docker**: installed (v29.3.1, Compose v5.1.1) but **the daemon was NOT running** at story-creation time — start Docker Desktop before `docker compose up` or any Testcontainers test. [Verified 2026-06-18]

### Scope decision — compose holds DBs only for now
The architecture's "Complete Monorepo Tree" lists `docker-compose.yml` as `postgres, redis, backend, frontend`, but the **Development Workflow** section says dev runs the backend via `mvn spring-boot:run` and the frontend via `npm run dev` with only **Postgres + Redis** in compose. This story implements the **dev shape** (DBs only). The `backend`/`frontend` container services + the native-Ollama deploy belong to **Story 1.8 (Tailscale + deploy-to-Mini)**. Note this in the compose file as a comment so it isn't mistaken for an omission. [Source: architecture.md#Development Workflow vs #Complete Monorepo Tree]

### Naming & data conventions (mandatory; will be enforced by every later DB story)
- **snake_case in the database, camelCase from Java outward.** Tables plural (`recommendations`), columns `created_at`, FK `<entity>_id`, indexes `idx_<table>_<cols>`. [Source: architecture.md#Naming Patterns]
- **Flyway file naming: `V<n>__description.sql`** (double underscore). Migrations live in `backend/src/main/resources/db/migration/`. [Source: architecture.md#Naming Patterns; #Complete Monorepo Tree]
- **`ddl-auto: validate` (or `none`) — Flyway owns the schema, Hibernate NEVER generates it.** Money columns will be `NUMERIC` (not float) and timestamps `timestamptz` (UTC) in future migrations. [Source: architecture.md#Format Patterns]
- Postgres 18 will hold relational + JSONB + pgvector all in one DB (no separate document/vector store). [Source: architecture.md#Decision 3]

### Source tree this story touches
```
argus/
├── docker-compose.yml                         # NEW (postgres + redis)
├── .env.example                               # UPDATE (drop MONGO_*, add POSTGRES_DB)
└── backend/
    ├── pom.xml                                # UPDATE (+flyway, +testcontainers test deps)
    └── src/
        ├── main/resources/
        │   ├── application.yml                # UPDATE (revert 1.1 hack; add datasource/redis/jpa/flyway)
        │   └── db/migration/V1__baseline.sql  # NEW (CREATE EXTENSION vector)
        └── test/java/com/argus/
            ├── TestcontainersConfiguration.java  # NEW
            └── ArgusApplicationTests.java         # UPDATE (or add DataLayerIntegrationTest)
```

### Testing standards
- **Use Testcontainers, not a manually-run compose stack, for the test** — the suite must be self-contained. Boot 4 supports `@ServiceConnection` so containers auto-wire datasource/redis properties; Flyway then runs against the throwaway Postgres and the migration creates pgvector (the `pgvector/pgvector` image ships the extension binary). [Source: architecture.md#Decision 3; Boot Testcontainers support]
- Redis `@ServiceConnection`: use a `GenericContainer<>("redis:8")` exposing 6379 annotated `@ServiceConnection(name = "redis")`, or the `com.redis:testcontainers-redis` module — dev's choice; keep it parent-managed where possible.
- `./mvnw test` must pass with Docker running. Tests mirror packages under `src/test/java/com/argus`. [Source: architecture.md#Structure Patterns]
- DoD: `docker compose up` healthy; backend boots & connects; Flyway `V1` applied; pgvector present; `/actuator/health` db+redis UP; `./mvnw test` green.

### Project Structure Notes
- Previous story (1.1) is **done** and committed (`9788bde`); the scaffold is the baseline. This story is the first to touch the `db/migration` tree and real connection config.
- No domain entities/repositories yet — keep the baseline migration to just the pgvector extension. Resist adding speculative tables; each feature story brings its own migration (forward-only, never edit an applied `V<n>`).
- `spring.datasource`/`spring.data.redis` go in the single `application.yml` for now; dev/prod **profiles** are introduced in **Story 1.4** (Model Gateway), so don't build the profile split here — just use env-var placeholders with local defaults.

### References
- [Source: epics.md#Story 1.2: Stand up the data layer] — user story + ACs.
- [Source: architecture.md#Decision 3 — Data Architecture] — Postgres 18 + JSONB + pgvector 0.8.2, Redis 8, Flyway, no Mongo.
- [Source: architecture.md#Decision 8 — Infrastructure & Deployment] — Docker Compose (Postgres + Redis + backend + frontend), Ollama native on host.
- [Source: architecture.md#Development Workflow] — dev runs DBs in compose, backend/frontend outside it.
- [Source: architecture.md#Naming Patterns / #Format Patterns] — snake_case, `V<n>__`, `ddl-auto` validate, NUMERIC money, UTC timestamptz.
- [Source: architecture.md#Complete Monorepo Tree] — `db/migration/` location; compose at root.
- [Source: 1-1-scaffold-the-monorepo.md] — the datasource/redis deferral this story must revert; existing `application.yml` and `pom.xml` state.
- [Source: deferred-work.md] — the profile-less temporary DB-disable config tracked for this story.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

**Session 2026-06-18. All 6 tasks complete; verified against a live compose stack + Testcontainers.**

Three Boot-4 / version gotchas hit and resolved (record for future DB/test stories):
- **PG18 Docker data mount changed.** Mounting the volume at `/var/lib/postgresql/data` makes `pgvector/pgvector:0.8.2-pg18` restart-loop with "in 18+, these Docker images are configured to store database data in a major-version-specific directory". Fix: mount at **`/var/lib/postgresql`** (the image places data in a `18/` subdir). Recreated the volume (`docker compose down -v`) after the fix.
- **Boot 4 Flyway needs the `spring-boot-flyway` module.** With only `flyway-core` + `flyway-database-postgresql`, the app started and connected to Postgres but Flyway **never ran** (zero Flyway log lines, no `flyway_schema_history`). Boot 4 extracted Flyway autoconfiguration into `org.springframework.boot:spring-boot-flyway` (per the modular-autoconfig pattern, like `DataSourceAutoConfiguration` in Story 1.1). Added that module (it pulls flyway-core transitively) → Flyway applied `V1`.
- **Testcontainers 2.x artifact renames.** Boot 4.0.7 BOM ships Testcontainers **2.0.5**, which renamed modules: `org.testcontainers:junit-jupiter` → **`testcontainers-junit-jupiter`**, `:postgresql` → **`testcontainers-postgresql`**. The old names build-fail with "version is missing". Core API (`PostgreSQLContainer`, `GenericContainer`, `DockerImageName`, `@ServiceConnection`) unchanged.
- **Reverted the Story-1.1 landmine** (the deferred-work item): removed the `DataSourceAutoConfiguration` exclude and the `management.health.redis.enabled: false` override from `application.yml`. Health now reports `db` + `redis` indicators, both UP.
- Minor: local `.env` line 31 (`SEC_EDGAR_USER_AGENT=Argus/1.0 (...)`) contains parentheses that break POSIX `source`/`. .env`. Not an app issue — Spring doesn't source `.env`; the app uses `${POSTGRES_*:default}` placeholders whose defaults match compose, so `mvn spring-boot:run` connects without any env export. Noted in case a future script tries to `source .env`.

### Completion Notes List

- ✅ **All 6 tasks complete; ACs 1–6 satisfied and verified.** Data layer stands up: Postgres 18 + pgvector 0.8.2 and Redis 8 via Docker Compose (both healthy), backend connects to both, Flyway applies the `V1` baseline (creates the `vector` extension), `/actuator/health` shows db + redis UP, and a Testcontainers integration suite proves it all (`./mvnw test` → 4/4 green).
- **Verification evidence:** `flyway_schema_history` row `1 | baseline | ok`; `pg_extension` → `pgvector 0.8.2`; health detail `db: UP (PostgreSQL)`, `redis: UP (8.8.0)`; tests `Tests run: 4, Failures: 0, Errors: 0`.
- **Left clean:** stopped the backend and ran `docker compose down` (named volumes `argus-pgdata`/`argus-redisdata` preserved). Nothing committed yet.
- **Status → review.** Suggest `bmad-code-review` (different model) before commit.

### File List

_New (relative to repo root):_
- `docker-compose.yml` (postgres pgvector + redis)
- `backend/src/main/resources/db/migration/V1__baseline.sql`
- `backend/src/test/java/com/argus/TestcontainersConfiguration.java`
- `backend/src/test/java/com/argus/DataLayerIntegrationTest.java`

_Modified:_
- `.env.example` (dropped `MONGO_*`, added `POSTGRES_DB`)
- `backend/pom.xml` (+`spring-boot-flyway`, +`flyway-database-postgresql`, +testcontainers test deps)
- `backend/src/main/resources/application.yml` (reverted 1.1 hack; added datasource/redis/jpa/flyway config)
- `backend/src/test/java/com/argus/ArgusApplicationTests.java` (import `TestcontainersConfiguration`)

_Untracked local (gitignored, not committed):_ `.env`

### Change Log

- 2026-06-18 — Story 1.2: stood up Postgres 18 (pgvector 0.8.2) + Redis 8 via Docker Compose; added Flyway (`spring-boot-flyway`) with `V1` baseline creating the pgvector extension; reverted the Story-1.1 datasource/redis-health deferral; added Testcontainers integration tests. All ACs verified; status → review.
