---
baseline_commit: 89e8bc6
---

# Story 1.5: Agent runtime + Redis Streams plumbing

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the builder,
I want a base Agent abstraction with scheduling and Redis Streams pub/consume,
so that real agents can be added consistently.

## Acceptance Criteria

1. **Given** the agent runtime, **When** a demo agent publishes an event to a Redis Stream, **Then** a **consumer group** receives it and **acknowledges** it (the message is delivered to the group's consumer and XACK'd; the group's pending-entries count returns to 0 after successful handling).
2. **Given** the runtime, **Then** agents run on **virtual threads** (Loom) driven by **`@Scheduled`** (the poll/dispatch executes on a virtual thread — `Thread.currentThread().isVirtual()` is true).
3. **Given** any published event, **Then** the **standard event envelope is enforced**: every event carries `eventId`, `type`, `occurredAt` (UTC), `version`, and `payload`. The publisher is the only way to emit events and always populates all five fields.
4. **Given** a new real agent later, **Then** it is added by implementing the base `Agent` abstraction and registering as a Spring bean — no bespoke Redis wiring per agent (the runtime discovers agents and manages their consumer groups + dispatch).
5. **Given** an exception thrown while handling an event, **Then** the runtime logs it and **continues** (never crashes); the failed message is left **unacknowledged** (stays in the pending-entries list for later inspection/claim — it is not redelivered by `lastConsumed` reads, so no poison-loop).

## Tasks / Subtasks

- [x] Task 1 — Event envelope + codec (AC: #3)
  - [x] `EventEnvelope` record (`eventId, type, occurredAt, version, payload`). No Lombok.
  - [x] `EventEnvelopeCodec` maps envelope ↔ `Map<String,String>` (payload as JSON, occurredAt ISO-8601 UTC). Note: owns a plain `new ObjectMapper()` rather than injecting one — there is no auto-configured `ObjectMapper` bean in this context (see Debug Log).
  - [x] Demo uses `demo.event`; real signal names come with their feature stories.
- [x] Task 2 — Publisher (AC: #3)
  - [x] `AgentEventPublisher.publish(streamKey, type, payload)` builds the full envelope (UUID, `Instant.now()`, version 1) and XADDs via `StringRedisTemplate.opsForStream().add()`. Single emit path → envelope enforced.
- [x] Task 3 — Base Agent abstraction (AC: #4)
  - [x] `Agent` interface (`name`, `streamKey`, default `consumerGroup`==name, `handle`). A real agent = a Spring bean implementing it; no per-agent Redis code.
- [x] Task 4 — Runtime: scheduling + consume/ack on virtual threads (AC: #1, #2, #5)
  - [x] `AgentConfig` (`@EnableScheduling` + `@EnableConfigurationProperties`); `spring.threads.virtual.enabled: true` in `application.yml`.
  - [x] `AgentRuntime`: `@PostConstruct` creates each agent's group idempotently (`createGroup(streamKey, ReadOffset.latest(), group)` — verified it passes `mkStream=true`, so the stream is auto-created; BUSYGROUP caught).
  - [x] `@Scheduled(fixedDelayString=...)` poll: per agent `read(Consumer.from(group,name), …, lastConsumed())` → `handle` → ack on success; on exception log + leave pending; method never throws.
  - [x] `AgentProperties` record (`pollIntervalMs=500`, `readCount=10`).
- [x] Task 5 — Demo agent (AC: #1, #2)
  - [x] `DemoAgent` (stream `argus:stream:demo`, group `demo-agent`): captures envelope + `isVirtual()`, exposes a `CountDownLatch` for tests.
- [x] Task 6 — Tests (AC: #1, #2, #3, #5)
  - [x] `EventEnvelopeCodecTest` (plain unit): 5-field round-trip incl. nested payload + empty payload.
  - [x] `AgentRuntimeIntegrationTest` (Testcontainers, dev): publish→receive→assert type/payload, `isVirtual()` true, pending==0 after ack. Added an error-path test (failing agent → message stays pending==1, runtime keeps running) for AC #5.
  - [x] `./mvnw test` → **12/12 green** (all prior suites still pass).
- [x] Task 7 — Verify
  - [x] `docker compose up -d` + `./mvnw spring-boot:run` (dev): boots clean, log `Created consumer group 'demo-agent' on stream 'argus:stream:demo'`, health UP; `redis-cli XINFO GROUPS` confirms the group (1 consumer, 0 pending). Stopped cleanly.

## Dev Notes

### Verified facts (2026-06-18)
- **spring-data-redis 4.0.6** is on the classpath (managed by Boot 4.0.7). Streams API confirmed: `StreamOperations.add/createGroup/read/acknowledge`, `Consumer`, `ReadOffset`, `StreamOffset`, `StreamReadOptions`, `StreamMessageListenerContainer`. Use `StringRedisTemplate.opsForStream()` (string keys/fields; store `payload` as a JSON string to avoid serializer config).
- **`spring.threads.virtual.enabled`** exists in Boot 4. Setting it `true` makes the `@Scheduled` task scheduler use virtual threads — that is how AC #2 ("agents run on virtual threads via @Scheduled") is satisfied. Java 25 has virtual threads GA. Confirm at runtime via `Thread.currentThread().isVirtual()`.
- No new dependencies — Redis + Jackson (`ObjectMapper`) are already present (data-redis + web). Do NOT add anything; if you think you need a dep, stop and reconsider.

### Architecture requirements (mandatory)
- **Decision 4 — Redis Streams for the agent bus** (persistent, consumer groups, ack, replay) so a high-impact signal is never silently lost if a consumer is busy. Plain Redis pub/sub is only for throwaway UI fan-out (NOT this story). [Source: architecture.md#Decision 4]
- **Event envelope `{ eventId, type, occurredAt (UTC), version, payload }`; one consumer group per agent.** Event names dot-notation, past tense. [Source: architecture.md#Communication Patterns]
- **Process pattern:** agents catch → log → (retry w/ backoff later) → never crash the runtime. This story implements catch+log+continue; full retry/Resilience4j/claim/DLQ is deferred to later agent stories — leave failed messages pending (don't fake retry). [Source: architecture.md#Process Patterns]
- `com.argus.agent` is the ★ load-bearing agent-runtime package: scheduler, virtual-thread executor, Redis Streams producer/consumer, lifecycle, base Agent. Build it clean-bounded (the runtime could later be extracted to its own process). [Source: architecture.md#Complete Monorepo Tree; #Project Structure & Boundaries]
- **Agent boundary:** agents never call each other directly — only publish/consume via Redis Streams. [Source: architecture.md#Architectural Boundaries]

### Key Spring Data Redis Streams API (consumer-group flow)
```java
// create group idempotently (stream auto-created)
try { redis.opsForStream().createGroup(streamKey, ReadOffset.latest(), group); }
catch (Exception e) { /* BUSYGROUP — group already exists; ignore */ }

// read new messages for this consumer in the group
List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
    Consumer.from(group, consumerName),
    StreamReadOptions.empty().count(count),
    StreamOffset.create(streamKey, ReadOffset.lastConsumed())); // ">"

// after successful handle:
redis.opsForStream().acknowledge(streamKey, group, record.getId());
```
- `ReadOffset.lastConsumed()` (`>`) delivers only never-before-delivered messages; an unacked (failed) message moves to the PEL and is NOT redelivered by `>` reads — so leaving it unacked is safe (no poison-loop) and correct for at-least-once. Pending count via `opsForStream().pending(streamKey, group)`.

### Profiles / config
- Add `spring.threads.virtual.enabled: true` to the **shared** `application.yml` (applies to dev + prod). Scheduling enabled via `@EnableScheduling` on an agent config class.
- `argus.agent` `@ConfigurationProperties`: `pollIntervalMs` (default 500), `readCount` (default 10). Record-based.
- The runtime + demo agent are active in **both** profiles (no model dependency); they only need Redis, which the dev compose provides.

### Source tree this story adds (`com.argus.agent` — currently only package-info.java)
```
backend/src/main/java/com/argus/agent/
├── EventEnvelope.java            # record (the standard envelope)
├── EventEnvelopeCodec.java       # envelope <-> Map<String,String> (JSON payload)
├── AgentEventPublisher.java      # single emit path (XADD), enforces envelope
├── Agent.java                    # base abstraction (interface)
├── AgentRuntime.java             # discover agents, create groups, @Scheduled poll+ack (virtual threads)
├── AgentProperties.java          # @ConfigurationProperties("argus.agent")
├── AgentConfig.java              # @EnableScheduling + @EnableConfigurationProperties
└── DemoAgent.java                # sample Agent for AC #1/#2 verification
backend/src/test/java/com/argus/agent/
├── EventEnvelopeCodecTest.java
└── AgentRuntimeIntegrationTest.java
```

### Testing standards
- Unit-test the codec with plain JUnit (no Spring/Docker).
- The runtime integration test boots the dev context → needs Testcontainers (Docker), consistent with Stories 1.2/1.4. Reuse the public `com.argus.TestcontainersConfiguration` (Redis container already wired via `@ServiceConnection`). [Source: 1-4-model-gateway-skeleton.md]
- Avoid Awaitility (not a dependency) — use a `CountDownLatch` with a bounded `await(timeout)`; keep `pollIntervalMs` small so the test is quick.
- `./mvnw test` must stay green across all suites.

### Project Structure Notes
- Previous stories 1.1/1.2 done & committed; 1.4 (Model Gateway) committed on `story/1.4-model-gateway` (status review, review skipped per user). Story 1.3 (Mini RAM/latency spike) deferred — see `deferred-work.md`. This story continues on a new branch off the latest commit (`89e8bc6`).
- `com.argus.agent` currently holds only `package-info.java` (1.1 scaffold) — this story fills it.
- This runtime is what Agent 1 (news, Story 4.x), Agent 7 (calendar), and Agent 5 (recommendations) will plug into. Keep `Agent` minimal but complete enough that those add no Redis wiring.

### References
- [Source: epics.md#Story 1.5: Agent runtime + Redis Streams plumbing] — user story + ACs.
- [Source: architecture.md#Decision 4 — Inter-Agent Event Bus] — Redis Streams (agents) vs pub/sub (UI).
- [Source: architecture.md#Communication Patterns] — event names, envelope, one consumer group per agent.
- [Source: architecture.md#Process Patterns] — catch/log/retry/never-crash.
- [Source: architecture.md#Complete Monorepo Tree / #Architectural Boundaries] — `agent` package responsibilities + agent boundary.
- [Source: 1-4-model-gateway-skeleton.md] — dev profile, public TestcontainersConfiguration, @ConfigurationProperties record pattern.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

**Session 2026-06-18. All 7 tasks complete; verified via 12/12 tests + a dev `spring-boot:run`.**

- **Verified the Spring Data Redis Streams API from the jars before coding** (`javap`): `createGroup(key, ReadOffset, group)` decompiles to `xGroupCreate(..., mkStream=true)`, so it auto-creates the stream — no manual MKSTREAM needed. `read(Consumer, StreamReadOptions, StreamOffset)` + `acknowledge(key, group, recordId)` confirmed.
- **No auto-configured `ObjectMapper` bean in this context.** First test run failed every `@SpringBootTest` with "No qualifying bean of type ObjectMapper" because `EventEnvelopeCodec` injected one. Boot 4 did not register an `ObjectMapper` here (likely a modular-autoconfig gap; web JSON converters configure their own). Fix: the codec owns a plain `new ObjectMapper()` — it only (de)serializes simple map payloads, so no Spring config needed. (Flagged for Story 1.6, which may want the Jackson autoconfig for REST.)
- **Virtual threads confirmed at runtime:** `spring.threads.virtual.enabled: true` → the `@Scheduled` poll runs on a virtual thread; the integration test asserts `Thread.currentThread().isVirtual()` and the demo log shows `virtualThread=true` on thread `scheduling-1`.
- **`lastConsumed()` (`>`)** delivers only new messages and does not redeliver un-acked ones, so leaving a failed message un-acked is safe (no poison-loop) and is what the error-path test asserts (pending stays 1).

### Completion Notes List

- ✅ **All 7 tasks complete; ACs #1–#5 satisfied.** `com.argus.agent` now has a real agent runtime: standard `EventEnvelope` (enforced by the single `AgentEventPublisher`), a base `Agent` interface, and an `AgentRuntime` that creates one consumer group per agent and polls/dispatches/acks on **virtual threads** via `@Scheduled`. A `DemoAgent` proves it end-to-end.
- **Tests: 12/12 green.** Codec round-trip (unit), runtime consume+ack on a virtual thread (Testcontainers), and the error path (failed handler → message left pending, runtime continues).
- **Runtime check:** dev boot creates the `demo-agent` group and stays healthy; `XINFO GROUPS` confirms it.
- **Scope discipline:** retry/backoff/claim/DLQ deliberately NOT built (deferred to later agent stories per the architecture's process pattern); failed messages just stay pending.
- **Left clean:** backend + compose stopped. Nothing committed yet. **Status → review.**

### File List

_New (relative to repo root):_
- `backend/src/main/java/com/argus/agent/EventEnvelope.java`
- `backend/src/main/java/com/argus/agent/EventEnvelopeCodec.java`
- `backend/src/main/java/com/argus/agent/Agent.java`
- `backend/src/main/java/com/argus/agent/AgentEventPublisher.java`
- `backend/src/main/java/com/argus/agent/AgentRuntime.java`
- `backend/src/main/java/com/argus/agent/AgentProperties.java`
- `backend/src/main/java/com/argus/agent/AgentConfig.java`
- `backend/src/main/java/com/argus/agent/DemoAgent.java`
- `backend/src/test/java/com/argus/agent/EventEnvelopeCodecTest.java`
- `backend/src/test/java/com/argus/agent/AgentRuntimeIntegrationTest.java`

_Modified:_
- `backend/src/main/resources/application.yml` (+`spring.threads.virtual.enabled: true`)

### Change Log

- 2026-06-18 — Story 1.5: agent runtime in `com.argus.agent` — standard event envelope + publisher, base `Agent` abstraction, Redis Streams consumer-group consume/ack on virtual-thread `@Scheduled`, demo agent. 12/12 tests green; dev boot verified. Status → review.
