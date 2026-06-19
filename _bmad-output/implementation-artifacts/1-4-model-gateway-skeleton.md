---
baseline_commit: effa9f1f89205de88e44d2cbcc20bb8e6fed9b26
---

# Story 1.4: Model Gateway skeleton

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Sequencing note:** Built **before** Story 1.3 (the Mac Mini RAM/latency spike, which is deferred until
> the Mini is available). Low risk: this story builds the *model-swappable* gateway skeleton; whatever 1.3
> concludes about the 26B model only adjusts the `prod` model binding/keep-alive, not the skeleton itself.
> The big-model (Ollama/Gemma) path is **written but not exercised on this laptop** (16GB can't host it) —
> it is exercised on the Mini later. Everything verifiable here runs under the `dev` profile with a mock model.

## Story

As the builder,
I want a Model Gateway wrapping Spring AI 2.0 with dev/prod profiles,
so that all LLM calls route through one component.

## Acceptance Criteria

1. **Given** Spring profile `dev`, **When** a test prompt is sent through the Model Gateway, **Then** it routes to a **small/mock model** (no Ollama or network dependency) and returns a response.
2. **Given** Spring profile `prod`, **Then** the gateway is configured to route to **Gemma 4 26B MoE via Ollama** (Spring AI Ollama `ChatModel`). *(Code path exists and wires under `prod`; not executed on this laptop — verified on the Mini in/after Story 1.3.)*
3. **Given** the `prod` big-model path, **Then** big-model access is **serialized (concurrency = 1)** via a gateway-owned semaphore, and the **keep-alive** is **configurable** (Ollama option, bound from config).
4. **Given** a primary-model failure, **Then** a **Claude Haiku fallback path exists** in the gateway (a **stub is acceptable** — a clearly-marked placeholder that is invoked on failure; no real Anthropic call wired yet).
5. **Given** the architecture's model boundary, **Then** the gateway lives in `com.argus.model` and is the **single** type callers use for LLM access (no direct `ChatModel`/`ChatClient`/Ollama/Anthropic calls elsewhere). The dev/prod model selection happens **inside** this component.
6. **Given** the test suite, **When** I run `./mvnw test`, **Then** unit tests prove: dev routing returns the mock response, the Haiku fallback is invoked on primary failure, and big-model calls are serialized (permit acquired/released). A `dev`-profile context test confirms the `ModelGateway` bean wires. (Existing data-layer Testcontainers tests stay green.)

## Tasks / Subtasks

- [x] Task 1 — Add Spring AI 2.0 to the build (AC: #1, #2)
  - [x] Imported `spring-ai-bom:2.0.0` (pom/import) in `<dependencyManagement>` via a `${spring-ai.version}` property.
  - [x] Added `spring-ai-starter-model-ollama` (BOM-managed). Pulls `spring-ai-client-chat`/`spring-ai-model` core + Ollama autoconfig.
  - [x] Anthropic starter intentionally NOT added — Haiku is a stub.
  - [x] Verified the 2.0 API from the resolved jars (`javap`): `ChatModel.call(Prompt)`, `ChatClient.builder(model).build()`, `ChatOptions.builder().build()` (no-arg `DefaultChatOptions` removed in 2.0), Ollama autoconfig at `org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration`.
- [x] Task 2 — Introduce dev/prod profiles + model config (AC: #1, #2, #3, #5)
  - [x] Shared infra stays in `application.yml`; added `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}` (default dev).
  - [x] `application-dev.yml`: excludes `OllamaChatAutoConfiguration` (verified class name) + sets `argus.model` (concurrency 1, dev-response).
  - [x] `application-prod.yml`: Ollama base-url + model (`${ARGUS_BIG_MODEL:gemma3:27b}`, provisional pending 1.3) + configurable `keep-alive`.
- [x] Task 3 — Implement the Model Gateway (`com.argus.model`) (AC: #1–#5)
  - [x] `ModelGatewayProperties` record (`@ConfigurationProperties("argus.model")`, `@DefaultValue`s) — concurrency, keepAlive, bigModel, devResponse. No Lombok.
  - [x] `ModelGateway` interface (`String generate(String)`) — single LLM entry point.
  - [x] `DefaultModelGateway`: builds `ChatClient` from the active `ChatModel`, `Semaphore(concurrency)` acquire/release in try/finally, Haiku fallback on `RuntimeException`.
  - [x] `HaikuFallback` interface + `StubHaikuFallback` (TODO-marked, logs + returns placeholder, invoked on failure).
  - [x] `MockChatModel` (`@Bean @Profile("dev")` via `ModelConfig`); prod uses the Ollama autoconfig bean. Boundary respected (all model access inside `com.argus.model`).
- [x] Task 4 — Tests (AC: #6)
  - [x] `DefaultModelGatewayTest` (plain JUnit, no Spring): routing returns content; fallback invoked on primary failure; serialization proven (max concurrent == 1, permits restored). Used real in-test `ChatModel` impls instead of Mockito (avoids stubbing the many `ChatModel` default methods).
  - [x] `ModelGatewayWiringTest` (`@SpringBootTest @ActiveProfiles("dev") @Import(TestcontainersConfiguration)`): gateway bean wired, returns the mock response. (Made `TestcontainersConfiguration` public so subpackage tests can import it.)
  - [x] `./mvnw test` → **8/8 green** (3 gateway + 1 wiring + 3 data-layer + 1 scaffold).
- [x] Task 5 — Verify
  - [x] `docker compose up -d` + `./mvnw spring-boot:run`: boots clean under the default `dev` profile (log: `The following 1 profile is active: "dev"`), health UP, **zero Ollama connection attempts** (autoconfig excluded). Stopped cleanly.
  - [x] Prod/Ollama path NOT run on this laptop — deferred to the Mini (ties to Story 1.3).

## Dev Notes

### Spring AI 2.0 — verified facts + cautions
- **Spring AI 2.0.0 is GA** and is the version aligned with the Spring Boot 4 baseline (architecture Decision 2). It is **not** managed by the Spring Boot BOM — import `org.springframework.ai:spring-ai-bom:2.0.0` in `<dependencyManagement>`. [Verified on Maven Central 2026-06-18]
- Starter artifacts verified present at 2.0.0: `spring-ai-starter-model-ollama`, `spring-ai-starter-model-anthropic` (Anthropic deferred — stub only), `spring-ai-client-chat`. Use the `spring-ai-starter-model-*` naming.
- ⚠️ **2.0 is a major version.** Confirm the actual `ChatClient` builder + fluent call API and the Ollama property keys (`spring.ai.ollama.*`, chat options incl. `keep-alive`/`keep_alive`, model name) against the 2.0 sources/docs before finalizing — do not assume 1.x signatures. We have repeatedly hit Boot-4-era renames in this project (DataSourceAutoConfiguration package move, spring-boot-flyway module, Testcontainers 2.x artifact renames) — treat Spring AI 2.0 with the same suspicion.

### Architecture requirements this story implements (mandatory)
- **Decision 1 — Model Gateway is the single home** for model selection, the budget-driven Claude Haiku fallback (FR-45), and the dev/prod model swap (the swappable LLM interface). Tiered model strategy: small model always-resident; **big model (Gemma 4 26B MoE) on-demand, serialized through the gateway (queue/semaphore, concurrency=1), with short configurable keep-alive**. [Source: architecture.md#Decision 1]
- **Decision 2 — pure Java/Spring + Spring AI 2.0 `ChatClient`.** No Python. All local + cloud LLM calls go through Spring AI wrapped by the gateway. [Source: architecture.md#Decision 2]
- **Model boundary (enforced):** "all LLM calls (agents, Ask AI, personas) flow through `com.argus.model`. Nothing calls Ollama/Anthropic directly." [Source: architecture.md#Architectural Boundaries; #Enforcement Guidelines]
- This is the load-bearing `model` package (★ in the monorepo tree) — build it clean-bounded. [Source: architecture.md#Complete Monorepo Tree]

### Profiles (introduced in THIS story)
- Story 1.2 deliberately deferred the dev/prod profile split to 1.4. Now introduce it. Shared infra (datasource/redis/flyway/actuator) stays in `application.yml`; only the **model** differs by profile.
- **Default active profile = `dev`** (local laptop): mock model, no Ollama needed. **`prod`** (Mac Mini): Ollama + Gemma. Activate prod via `--spring.profiles.active=prod` on the Mini.
- Current `application.yml` (from Story 1.2) contains: `spring.application.name`, `spring.datasource`, `spring.jpa` (`ddl-auto: validate`, `open-in-view: false`), `spring.flyway.enabled`, `spring.data.redis`, `management.*`. Preserve all of it as shared config; add the model config in the profile files. [Source: 1-2-stand-up-the-data-layer.md]

### Two-ChatModel-bean trap (avoid)
If the Ollama starter is on the classpath, its autoconfig creates an `OllamaChatModel` bean. Under `dev` you also define a `MockChatModel` bean → two `ChatModel` beans → ambiguous injection / ChatClient autoconfig failure. Resolution: **exclude the Spring AI Ollama autoconfiguration in `application-dev.yml`** so dev has exactly the mock; let prod use the Ollama bean. (Find the exact autoconfig class from the starter — same technique as our earlier Boot-4 excludes.)

### Source tree this story touches
```
backend/
├── pom.xml                                       # UPDATE (+spring-ai-bom import, +ollama starter)
└── src/
    ├── main/
    │   ├── java/com/argus/model/                 # the Model Gateway (currently only package-info.java)
    │   │   ├── ModelGateway.java                 # NEW interface
    │   │   ├── DefaultModelGateway.java          # NEW impl (ChatClient + Semaphore + fallback)
    │   │   ├── ModelGatewayProperties.java       # NEW @ConfigurationProperties("argus.model")
    │   │   ├── MockChatModel.java                # NEW dev ChatModel
    │   │   ├── HaikuFallback.java                # NEW interface
    │   │   ├── StubHaikuFallback.java            # NEW stub impl (TODO marker)
    │   │   └── ModelConfig.java                  # NEW @Configuration: profile beans (dev mock / ChatClient)
    │   └── resources/
    │       ├── application.yml                   # UPDATE (default profile=dev; keep shared infra config)
    │       ├── application-dev.yml               # NEW (mock model; exclude Ollama autoconfig)
    │       └── application-prod.yml              # NEW (Ollama Gemma + keep-alive)
    └── test/java/com/argus/model/
        ├── DefaultModelGatewayTest.java          # NEW plain unit tests (Mockito)
        └── ModelGatewayWiringTest.java           # NEW @SpringBootTest dev-profile wiring (Testcontainers)
```

### Naming / conventions
- `PascalCase` classes, package `com.argus.model`. Java **records** for properties/DTOs (no Lombok). [Source: architecture.md#Naming Patterns]
- Structured logging later feeds the Ops dashboard; for the gateway, log model/tier + durationMs at minimum (don't over-build). [Source: architecture.md#Process Patterns]
- Keep the public surface minimal — this is a skeleton; resist adding pre-warm, real Haiku, tiering policy, or budget logic (those are later stories: 1.5 runtime, Epic 6 Agent 5, Epic 10 cost). Implement only what ACs #1–#6 require.

### Testing standards
- Prefer **plain JUnit + Mockito unit tests** for gateway logic (routing/serialization/fallback) — no Spring context, no Docker, fast and deterministic. Mockito is already available (seen self-attaching in earlier runs).
- The single `@SpringBootTest` wiring test must use `@ActiveProfiles("dev")` and `@Import(TestcontainersConfiguration.class)` (any full context boot now needs the datasource → Testcontainers/Docker, per Story 1.2). [Source: 1-2-stand-up-the-data-layer.md Testing]
- Do not add a test that requires a running Ollama — the prod path is verified on the Mini later.

### Project Structure Notes
- Previous stories 1.1 (scaffold) and 1.2 (data layer) are **done & committed** on branches `story/1.1-scaffold-monorepo` / `story/1.2-data-layer` (not yet merged to main). This story continues on a new branch off the latest.
- **Story 1.3 is deferred** (Mac Mini RAM/latency spike — can't run on this 16GB laptop). See `deferred-work.md`. 1.4 is intentionally sequenced ahead of it.
- `com.argus.model` currently holds only `package-info.java` from the 1.1 scaffold — this story fills it.

### References
- [Source: epics.md#Story 1.4: Model Gateway skeleton] — user story + ACs.
- [Source: architecture.md#Decision 1 — Model Resource Governance] — tiered model, gateway responsibilities, serialization, keep-alive, Haiku fallback.
- [Source: architecture.md#Decision 2 — Agent Orchestration Runtime] — Spring AI 2.0 ChatClient, pure Java.
- [Source: architecture.md#Architectural Boundaries / #Enforcement Guidelines] — model boundary (all LLM calls via `com.argus.model`).
- [Source: architecture.md#Complete Monorepo Tree] — `model` package responsibilities (★ load-bearing).
- [Source: 1-2-stand-up-the-data-layer.md] — current `application.yml` shape; Testcontainers test pattern; profiles deferred to 1.4.
- [Source: deferred-work.md] — Story 1.3 deferral (Mini dependency).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

**Session 2026-06-18. All 5 tasks complete; verified via tests + a dev-profile `spring-boot:run`.**

- **Spring AI 2.0 API verified from jars before coding** (not assumed): `ChatModel.call(Prompt)→ChatResponse` is the one abstract method (`call(String)` is a default); `ChatClient.builder(chatModel).build()` then `.prompt().user(p).call().content()`; messages/response built via `new ChatResponse(List.of(new Generation(new AssistantMessage(text))))`.
- **`DefaultChatOptions` no-arg constructor removed in 2.0.** First compile failed — it's now an 8-arg record. Fix: `ChatOptions.builder().build()` for the mock's `getDefaultOptions()`. (Same "major-version renamed/changed API" pattern as Boot 4 / Testcontainers 2.x earlier.)
- **Two-ChatModel-bean trap handled:** the Ollama starter autoconfigures an `OllamaChatModel`. Under `dev` we add a `MockChatModel` bean → would be ambiguous. Excluded `org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration` in `application-dev.yml` (class name confirmed via `unzip -l` of the autoconfigure jar). Verified at runtime: dev boot shows **zero Ollama activity**.
- **`TestcontainersConfiguration` made public** (was package-private from Story 1.2) so `com.argus.model` tests can `@Import` it.
- **Mockito avoided for `ChatModel`** in unit tests — wrapping a Mockito mock in `ChatClient` risks NPEs on the model's many default methods; used small real `ChatModel` impls (failing + concurrency-tracking) instead, which is more robust.
- Prod/Ollama path is written but **not executed here** (16GB laptop can't host Gemma; Ollama not installed) — exercised on the Mini in/after Story 1.3.

### Completion Notes List

- ✅ **All 5 tasks complete; ACs #1–#6 satisfied.** The `com.argus.model` Model Gateway is the single LLM entry point: dev→mock model, prod→Ollama/Gemma (config present, run on Mini later), big-model access serialized via `Semaphore(1)`, configurable keep-alive, and a wired-but-stubbed Haiku fallback.
- **Tests: 8/8 green** (`./mvnw test`): gateway routing, fallback-on-failure, serialization (max concurrent == 1), dev-profile wiring, + the 4 existing data-layer/scaffold tests.
- **Runtime check:** `spring-boot:run` under default `dev` profile boots clean, health UP, no Ollama attempts.
- **AC #2 caveat:** prod routing is verified by configuration + wiring only; actual Gemma-via-Ollama execution is deferred to the Mac Mini (Story 1.3). The Gemma model tag (`gemma3:27b`) is a provisional placeholder, env-overridable.
- **Left clean:** backend + compose stopped. Nothing committed yet. **Status → review.**

### File List

_New (relative to repo root):_
- `backend/src/main/java/com/argus/model/ModelGateway.java`
- `backend/src/main/java/com/argus/model/DefaultModelGateway.java`
- `backend/src/main/java/com/argus/model/ModelGatewayProperties.java`
- `backend/src/main/java/com/argus/model/ModelGatewayException.java`
- `backend/src/main/java/com/argus/model/HaikuFallback.java`
- `backend/src/main/java/com/argus/model/StubHaikuFallback.java`
- `backend/src/main/java/com/argus/model/MockChatModel.java`
- `backend/src/main/java/com/argus/model/ModelConfig.java`
- `backend/src/main/resources/application-dev.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/test/java/com/argus/model/DefaultModelGatewayTest.java`
- `backend/src/test/java/com/argus/model/ModelGatewayWiringTest.java`

_Modified:_
- `backend/pom.xml` (+spring-ai-bom import, +spring-ai-starter-model-ollama)
- `backend/src/main/resources/application.yml` (default `spring.profiles.active=dev`)
- `backend/src/test/java/com/argus/TestcontainersConfiguration.java` (package-private → public)

### Change Log

- 2026-06-18 — Story 1.4: Model Gateway skeleton in `com.argus.model` (Spring AI 2.0 `ChatClient`); dev/prod profiles (dev mock model, prod Ollama/Gemma); `Semaphore(1)` serialization + configurable keep-alive; stubbed Haiku fallback. 8/8 tests green; dev boot verified. Status → review.
