---
baseline_commit: f69471aa5178223b8a53167aa690342d8d0f6cc7
---

# Story 1.1: Scaffold the monorepo

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the builder,
I want the backend and frontend scaffolded in one repo,
so that I have a runnable baseline to build on.

## Acceptance Criteria

1. **Given** the existing repo root, **When** I run the documented Spring Initializr command (Maven, Java 25, Spring Boot 4.0.x) into `backend/`, **Then** a Spring Boot app exists with `groupId=com.argus`, `artifactId=argus-backend`, packaging `jar`.
2. **Given** the existing repo root, **When** I run the documented `create-next-app@latest` command (Next 16, TypeScript, Tailwind v4, App Router, `src/` dir, Turbopack, import alias `@/*`) into `frontend/`, **Then** a Next.js app exists.
3. **Given** the scaffolded backend, **When** I run `mvn spring-boot:run`, **Then** the app starts locally and the Actuator health endpoint (`GET /actuator/health`) returns `{"status":"UP"}`.
4. **Given** the scaffolded frontend, **When** I run `npm run dev`, **Then** the default page renders at `http://localhost:3000`.
5. **Given** the backend, **Then** the feature-package skeleton exists under `src/main/java/com/argus/`: `config, common, model, agent, marketdata, portfolio, intelligence, calendar, recommendation, persona, conversation, notification, cost, ops, security` (each as a Java package), and `src/test/java/com/argus/` mirrors it.
6. **Given** the frontend, **Then** the folder skeleton exists under `src/`: `app, features, components/ui, lib, hooks, stores`.
7. **Given** both apps, **Then** the repo root `README.md` documents the two exact init commands used and how to run each app, and the obsolete empty root `src/` directory is removed.

## Tasks / Subtasks

- [x] Task 1 — Scaffold backend with Spring Initializr (AC: #1, #3, #5)
  - [x] Pin `bootVersion` to the latest stable `4.0.x` at scaffold time → resolved to **`4.0.7`** (see Debug Log: Initializr metadata returned bogus legacy `.RELEASE` IDs absent from Maven Central; corrected against Central).
  - [x] Run the init command **omitting `data-mongodb`** (dependencies = `web,websocket,data-jpa,data-redis,actuator,validation,postgresql`). Boot 4 emitted `webmvc`/`websocket` starters + paired `-test` starters.
  - [x] Unzip into `backend/`. Confirmed `backend/pom.xml`, `ArgusApplication.java`, `application.yml` exist.
  - [x] Create the feature packages listed in AC #5 (each with a `package-info.java`). Mirror under `src/test/java/com/argus/` (`.gitkeep` per package).
  - [x] `./mvnw spring-boot:run` → `curl localhost:8080/actuator/health` returned `200 {"status":"UP"}`. `./mvnw test` green (`contextLoads`, 1/1). Datasource autoconfig deferred so context boots without a DB (see Debug Log).
- [x] Task 2 — Scaffold frontend with create-next-app (AC: #2, #4, #6)
  - [x] Ran `create-next-app@latest` with the documented flags → **Next.js 16.2.9 · React 19.2.4** (`app-tw` template).
  - [x] Created the frontend folder skeleton from AC #6 under `frontend/src/` (`features, components/ui, lib, hooks, stores` with `.gitkeep`; `app/` already present).
  - [x] `npm run dev` → `http://localhost:3000/` returned `200` with the default "Create Next App" page.
- [x] Task 3 — Repo hygiene + docs (AC: #7)
  - [x] Removed the empty root `src/` directory (`ProjectX.iml` left as-is; gitignored).
  - [x] Verified the root `.gitignore` already covers `target/`, `node_modules/`, `.next/`, `.env` — left untouched.
  - [x] Wrote root `README.md`: the two exact init commands, how to run each app, the stack, and the monorepo layout.
- [x] Task 4 — Verify clean state
  - [x] `git status` shows `backend/`, `frontend/`, `README.md`, `_bmad-output/implementation-artifacts/` as new; `node_modules/`, `target/`, `.next/`, `.env` confirmed ignored (not tracked).
  - [x] Both apps started and stopped cleanly this session (backend health UP; frontend page 200); no lingering processes.

### Review Findings (code review 2026-06-18)

_3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Outcome: **7/7 ACs satisfied, no code bugs.** 0 decision-needed, 4 patch, 2 defer, 4 dismissed._

Patch (doc/hygiene, unambiguous) — all applied 2026-06-18:
- [x] [Review][Patch] README backend `curl` isn't self-contained reproducible — added a version-correction note about the `.RELEASE` gotcha [README.md §"How this monorepo was scaffolded"]
- [x] [Review][Patch] Story File List lists `HELP.md` as committed, but `backend/.gitignore` ignores it — corrected the File List note [this file → File List]
- [x] [Review][Patch] Debug Log session header was stale — rewritten to reflect both sessions / completion [this file → Debug Log]
- [x] [Review][Patch] README Node floor "20+" → "20.9+" (Next.js 16 minimum) [README.md §Prerequisites]

Deferred (real, tracked, not actionable now):
- [x] [Review][Defer] `backend/.gitattributes` forces `*.cmd eol=crlf` but `mvnw.cmd` blob is LF → fresh-clone dirty/renormalize warning on Windows [backend/.gitattributes] — deferred: Spring Initializr default, Mac-only solo project, Windows-only impact
- [x] [Review][Defer] Temporary DB-disabling config (`DataSourceAutoConfiguration` exclude + redis health off) lives in the single default `application.yml` with no profile guard — only an inline comment prevents it being silently carried past Story 1.2 [application.yml] — deferred: Story 1.2 reverts it (its first task wires the real datasource); also flagged in project memory

Dismissed (noise / false positive / by-design): Turbopack "missing" from npm scripts (false positive — Turbopack is the **default** bundler in Next 16, so `--turbopack` is a no-op; AC #2 satisfied); `/actuator/health` UP "masks" absent data layer (by-design for scaffold, AC #3 only requires UP); missing trailing newlines (cosmetic); implicit `<packaging>jar` (Maven default, AC satisfied).

## Dev Notes

### CRITICAL — MongoDB is dropped (do not add it)
The architecture's **Starter Template Evaluation** section (and its sample `curl` command) still lists `data-mongodb` and a Mongo container — **this is stale**. **Architecture Decision 3 (keystone, PRD-reconciled)** replaced the original four-store plan with **PostgreSQL 18 + Redis 8 only**. MongoDB and the separate vector DB are gone (JSONB + pgvector live inside Postgres). **Do NOT include `data-mongodb` in the Spring Initializr dependencies**, and do not add a Mongo service anywhere. [Source: architecture.md#Decision 3 — Data Architecture; architecture.md#Starter Template Evaluation]

### Stack versions are LOCKED by the architecture — do not substitute
- Backend: **Java 25 + Maven + Spring Boot 4.0.x** (web, WebSocket, JPA, Redis, Actuator, validation, postgresql). Java **records** replace Lombok — do not add Lombok. Spring Security is **deferred** (custom PIN/biometric auth comes in Epic 2) — do not add the security starter. [Source: architecture.md#Starter Template Evaluation; architecture.md#Decision 5]
- Frontend: **TypeScript + App Router + Tailwind v4 + Turbopack**, `src/` dir, import alias `@/*`. [Source: architecture.md#Decision 7; epics.md#Story 1.1]
- ⚠️ Note: the older "Confirmed Stack" notes (Java 21 / Boot 3.x / Next 14) are **superseded** — the architecture + epics are authoritative: Java 25, Boot 4.0.x, Next 16. Use the architecture values.

### Exact init commands (corrected — Mongo removed)
Backend (resolve the real latest `4.0.x` for `bootVersion`):
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d bootVersion=4.0.x -d packaging=jar \
  -d groupId=com.argus -d artifactId=argus-backend -d name=argus \
  -d dependencies=web,websocket,data-jpa,data-redis,actuator,validation,postgresql \
  -o backend.zip
unzip backend.zip -d backend && rm backend.zip
```
Frontend:
```bash
npx create-next-app@latest frontend \
  --typescript --tailwind --eslint --app --src-dir --turbopack --import-alias="@/*"
```
[Source: architecture.md#Starter Template Evaluation — command adjusted to drop `data-mongodb` per Decision 3]

### Source tree this story creates
Target monorepo layout (this story builds `backend/` + `frontend/` skeletons; `docker-compose.yml`, Flyway, and Docker services arrive in **Story 1.2**, NOT here): [Source: architecture.md#Complete Monorepo Tree]
```
argus/  (repo root = /Users/gaurav.oli/Documents/Projects/ProjectX)
├── README.md                         # extend in this story
├── .gitignore                        # ALREADY EXISTS — do not regenerate
├── .env.example                      # ALREADY EXISTS — do not touch
├── backend/                          # NEW — Spring Boot 4 · Java 25 · Maven
│   ├── pom.xml
│   ├── src/main/java/com/argus/{ArgusApplication.java, config, common, model,
│   │     agent, marketdata, portfolio, intelligence, calendar, recommendation,
│   │     persona, conversation, notification, cost, ops, security}
│   ├── src/main/resources/application.yml   # rename from .properties if you prefer YAML
│   └── src/test/java/com/argus/…     # mirrors packages
└── frontend/                         # NEW — Next.js 16 · TS · Tailwind v4
    └── src/{app, features, components/ui, lib, hooks, stores}
```

### Naming & structure rules (mandatory, enforced repo-wide)
- **Feature/domain-based packages**, never layer-first top-level dirs (no top-level `controllers/`, `services/`). Each `com.argus.<domain>` package later owns its own controller/service/repository/domain. [Source: architecture.md#Structure Patterns]
- Java: `PascalCase` classes, packages `com.argus.<domain>`. Frontend: `PascalCase.tsx` components, `useXxx` hooks, `camelCase` utilities, Zustand slices per domain in `stores/`. [Source: architecture.md#Naming Patterns]
- The snake_case↔camelCase DB boundary, RFC 9457 errors, BigDecimal money, etc. don't apply yet (no DB/endpoints in this story) but the package homes you create now are where they'll live — place them correctly. [Source: architecture.md#Enforcement Guidelines]

### Existing repo state (read before you start)
- Repo root already contains: `.gitignore`, `.env.example`, `_bmad-output/` (committed planning docs), `_bmad/`, `BMAD-METHOD/` (gitignored reference clone), `docs/` (empty), and an **empty `src/` dir at root** → delete it (the real source lives under `backend/src`). [Verified via `ls` 2026-06-17]
- The root `.gitignore` already ignores `target/`, `node_modules/`, `.next/`, `build/`, `dist/`, `.env`/`.env.*` (keeps `.env.example`), `.idea/`, `*.iml`, `BMAD-METHOD/`. **Do not regenerate** the generators' own `.gitignore` over it — let `backend/.gitignore` and `frontend/.gitignore` (created by the generators) coexist; they scope to their own subdirs. [Verified via reading `.gitignore` 2026-06-17]

### App-startup caveat (so AC #3/#4 pass without the data layer)
- Story 1.2 stands up Postgres + Redis. For **this** story they aren't running. Spring Boot's JPA/Redis autoconfiguration can fail fast on missing connections. If `mvn spring-boot:run` fails to start purely because it can't reach a datasource/Redis, the acceptable fix is to defer datasource initialization (e.g. `spring.sql.init.mode=never`, lazy datasource, or no datasource URL configured yet) so the context boots and `/actuator/health` is reachable — **without removing the JPA/Redis dependencies**. Document whatever you do in `application.yml`. The real connections get wired in Story 1.2. [Source: architecture.md#Decision Impact Analysis — sequence step 1 (scaffold) precedes step 2 (data layer)]

### Testing standards
- Backend: tests mirror packages under `src/test/java/com/argus`. The Initializr includes a default `ArgusApplicationTests` context-load test (JUnit 5). For this story, a passing `contextLoads()` is the bar — do not add a full test harness yet. [Source: architecture.md#Structure Patterns]
- Frontend: tests co-located as `*.test.tsx`. No test framework is required by this story (create-next-app does not add one by default); leave for a later story.
- Definition of done for this story = both AC startup checks pass and `./mvnw test` (default context-load test) is green.

### Project Structure Notes
- This is a **greenfield** scaffold over an existing planning-only repo — no prior source code to integrate with. No previous implementation story exists (this is Story 1.1).
- Deployment shape is a **single-process modular monolith** (one Spring Boot app + one Next.js app). Do not split into multiple JVMs/modules. [Source: architecture.md#Project Structure & Boundaries]
- Detected variance + resolution: architecture's starter-eval `curl` includes `data-mongodb`; **resolved against Decision 3 → omit Mongo.** This is the only intentional deviation from the verbatim architecture command.

### References
- [Source: epics.md#Epic 1: Foundation & Walking Skeleton] — epic objective: deployed, Tailscale-accessible empty Argus with model stack proven on hardware before feature work.
- [Source: epics.md#Story 1.1: Scaffold the monorepo] — user story + ACs.
- [Source: architecture.md#Starter Template Evaluation] — generators, init commands, build tool (Maven), Lombok→records, Security deferred.
- [Source: architecture.md#Decision 3 — Data Architecture] — Postgres 18 + Redis 8 only; Mongo dropped.
- [Source: architecture.md#Decision 7 — Frontend Architecture] — TS, App Router, Tailwind v4, Turbopack.
- [Source: architecture.md#Complete Monorepo Tree] — full backend/frontend folder skeleton.
- [Source: architecture.md#Implementation Patterns & Consistency Rules] — naming + feature-based structure.
- [Source: architecture.md#Decision Impact Analysis] — implementation sequence (scaffold is step 1; data layer is step 2).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

**Story implemented across two sessions: 2026-06-17 (Task 1, backend — then paused at user request) and 2026-06-18 (Tasks 2–4, frontend + hygiene + verification). All tasks complete.**

- **Boot version correction (important for future stories):** `start.spring.io/metadata/client` returned legacy version IDs (`4.0.7.RELEASE`, default `4.1.0.RELEASE`, Java default 17). These `.RELEASE`-suffixed artifacts **do not exist in Maven Central** (Spring dropped the suffix at 2.4). The build failed `Non-resolvable parent POM ... 4.0.7.RELEASE`. Queried Central's `maven-metadata.xml` directly → real latest 4.0.x is **`4.0.7`** (4.1.0 is GA but architecture locked the 4.0.x line). Fixed `pom.xml` parent version to `4.0.7`. **Lesson: trust Maven Central for version IDs, not the Initializr metadata in this env.**
- **Base-package restructure:** Boot 4 generated everything under `com.argus.argus_backend` (artifactId-derived). Architecture mandates base package `com.argus` with feature subpackages directly beneath. Moved `ArgusApplication`/`ArgusApplicationTests` to `com.argus`, deleted the `argus_backend` subpackage.
- **Boot 4 starter rename (informational):** `web` → `spring-boot-starter-webmvc`; every starter ships a paired `spring-boot-starter-*-test`. Not an error — the new Boot 4 module layout.
- **Deferred datasource (per story's startup caveat):** classpath has data-jpa/data-redis/postgresql but no DB until Story 1.2. Context failed with `Failed to determine a suitable driver class`. Resolved by excluding `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` (Boot-4 package; JPA autoconfig back off without a DataSource) and disabling the Redis health indicator so `/actuator/health` is UP. **Story 1.2 MUST remove both** and wire real Postgres+Redis (flagged in `application.yml` comments).

### Completion Notes List

- ✅ **Task 1 complete (AC #1, #3, #5).** Backend scaffolded: Maven, Java 25, Spring Boot **4.0.7**, deps `webmvc,websocket,data-jpa,data-redis,actuator,validation,postgresql` (**no MongoDB**, per Decision 3). Base package `com.argus`; all 15 feature packages created with `package-info.java`; test root mirrors them. `./mvnw test` green; live `/actuator/health` → UP.
- ✅ **Task 2 complete (AC #2, #4, #6)** — session 2026-06-18. Frontend scaffolded via `create-next-app@latest` → **Next.js 16.2.9 / React 19.2.4**, TS + Tailwind v4 + Turbopack + App Router + `src/` + alias `@/*`. Folder skeleton (`features, components/ui, lib, hooks, stores`) added under `src/` with `.gitkeep`. `npm run dev` → `localhost:3000` returned 200 (default page).
- ✅ **Task 3 complete (AC #7).** Removed empty root `src/`; verified root `.gitignore` already covers `target/`/`node_modules/`/`.next/`/`.env` (left untouched); wrote root `README.md` (init commands, run steps, stack, layout, package structure).
- ✅ **Task 4 complete.** `git status` clean: only `backend/`, `frontend/`, `README.md`, `_bmad-output/implementation-artifacts/` untracked; build/dep dirs confirmed ignored. Both apps start/stop cleanly; no lingering processes.
- **ALL ACs satisfied → Status `review`.** Suggest running `bmad-code-review` (ideally a different model) before marking done. Nothing committed (awaiting user).

### File List

_Backend scaffold (new), relative to repo root:_
- `backend/pom.xml` (parent version corrected to 4.0.7)
- `backend/src/main/java/com/argus/ArgusApplication.java`
- `backend/src/main/java/com/argus/{config,common,model,agent,marketdata,portfolio,intelligence,calendar,recommendation,persona,conversation,notification,cost,ops,security}/package-info.java` (15 files)
- `backend/src/main/resources/application.yml` (replaces generated `application.properties`; temporary datasource-exclusion + redis-health-disable for scaffold)
- `backend/src/test/java/com/argus/ArgusApplicationTests.java`
- `backend/src/test/java/com/argus/<15 feature pkgs>/.gitkeep` (test-package mirror)
- `backend/{mvnw,mvnw.cmd,.mvn/,.gitignore,.gitattributes}` (standard Initializr output; the generated `HELP.md` is gitignored by `backend/.gitignore`, so it is intentionally not tracked)

_Frontend scaffold (new), relative to repo root:_
- `frontend/` — full `create-next-app` output: `package.json`, `package-lock.json`, `next.config.ts`, `tsconfig.json`, `eslint.config.mjs`, `postcss.config.mjs`, `next-env.d.ts`, `AGENTS.md`, `CLAUDE.md`, `README.md`, `public/`
- `frontend/src/app/{layout.tsx,page.tsx,globals.css,favicon.ico}` (generated)
- `frontend/src/{features,components/ui,lib,hooks,stores}/.gitkeep` (folder skeleton, AC #6)

_Repo root:_
- `README.md` (new — monorepo overview, init commands, run instructions)
- removed empty `src/` directory

### Change Log

- 2026-06-17 — Task 1: backend Spring Boot 4.0.7 scaffold created, restructured to `com.argus`, feature packages added, boots with health UP, tests green. (Paused at user request.)
- 2026-06-18 — Tasks 2–4: frontend Next.js 16.2.9 scaffold + folder skeleton (`npm run dev` 200); removed empty root `src/`; added root `README.md`; verified clean git state. All ACs met → Status `review`.