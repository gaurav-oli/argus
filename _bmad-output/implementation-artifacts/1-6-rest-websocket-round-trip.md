---
baseline_commit: 281db9f
---

# Story 1.6: REST + WebSocket round-trip

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the builder,
I want REST and STOMP WebSocket wired end-to-end,
so that the frontend can call APIs and receive live pushes.

## Acceptance Criteria

1. **Given** the backend, **When** a client calls a sample `/api/...` endpoint, **Then** it receives a **typed JSON response** (the resource directly — no envelope wrapper).
2. **Given** an error (e.g. a not-found / bad request on the sample endpoint), **Then** the response is an **RFC 9457 Problem Details** body (`application/problem+json` with `type`/`title`/`status`/`detail`), produced by a global handler — one error shape everywhere.
3. **Given** a STOMP WebSocket endpoint, **When** the backend publishes to a `/topic/...` destination, **Then** a connected STOMP client **receives it live** (verified by a real STOMP-over-WebSocket client in an integration test).
4. **Given** the frontend, **Then** typed access utilities exist — `lib/apiClient.ts` (typed fetch + RFC 9457 error parsing) and `lib/wsClient.ts` (STOMP client via `@stomp/stompjs`) — that compile and encapsulate the REST + WS access the dashboard (Story 1.7) will use. *(Visual browser round-trip is demonstrated when the shell exists in 1.7; this story proves the wire at the backend boundary + typed client layer.)*
5. **Given** REST conventions, **Then** paths are plural kebab-case under `/api` with **no version prefix**, success returns the resource directly, and CORS allows the local frontend origin (`http://localhost:3000`) in dev.

## Tasks / Subtasks

- [x] Task 1 — Sample REST endpoint with typed JSON (AC: #1, #5)
  - [x] `SystemInfoController` `GET /api/system-info` → `SystemInfo` record (name/version/profile/time) as JSON, no envelope, plural kebab-case, no version prefix.
  - [x] `GET /api/system-info/{key}` throws `NotFoundException` for unknown keys (drives AC #2).
- [x] Task 2 — RFC 9457 Problem Details (AC: #2)
  - [x] `spring.mvc.problemdetails.enabled: true` in `application.yml`.
  - [x] `GlobalExceptionHandler` (`@RestControllerAdvice extends ResponseEntityExceptionHandler`) maps `NotFoundException` → `ProblemDetail` (type/title/status/detail). Verified live: `404 application/problem+json`.
- [x] Task 3 — STOMP WebSocket (AC: #3)
  - [x] `WebSocketConfig` (`@EnableWebSocketMessageBroker`): endpoint `/ws` (`setAllowedOriginPatterns("*")` — Tailscale is the boundary), `enableSimpleBroker("/topic")`, app prefix `/app`.
  - [x] `LivePushService.publish(topic, payload)` wraps `SimpMessagingTemplate`. `CorsConfig` allows `:3000` → `/api/**`.
- [x] Task 4 — Frontend typed clients (AC: #4)
  - [x] `npm install @stomp/stompjs@7.3.0`.
  - [x] `src/lib/apiClient.ts` (typed `fetch`, base-url env, RFC 9457 → typed `ApiError`, `SystemInfo` interface + `getSystemInfo()`).
  - [x] `src/lib/wsClient.ts` (`@stomp/stompjs` wrapper: `subscribeToTopic<T>`). `npx tsc --noEmit` clean.
- [x] Task 5 — Tests (AC: #1, #2, #3)
  - [x] `SystemInfoControllerTest` (`@WebMvcTest`, no Docker): typed JSON 200; unknown key → `problem+json` 404 with title/status/detail.
  - [x] `StompRoundTripIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + Testcontainers): real `WebSocketStompClient` subscribes to `/topic/demo`, receives a `LivePushService` publish.
  - [x] `./mvnw test` → **15/15 green** (after fixing a virtual-thread regression — see Debug Log).
- [x] Task 6 — Verify
  - [x] Live `curl`: `/api/system-info` → typed JSON `{name,version,profile,time}` (200 `application/json`); `/api/system-info/nope` → RFC 9457 (404 `application/problem+json`). Stopped cleanly.

## Dev Notes

### Verified facts (2026-06-18)
- **RFC 9457 is native in Spring Boot 4**: `org.springframework.http.ProblemDetail` + `ErrorResponse` present; the `spring.mvc.problemdetails.enabled` property exists in the webmvc autoconfig. Use `ProblemDetail` (NOT a custom error DTO). [Source: architecture.md#Decision 6]
- **STOMP**: `spring-boot-starter-websocket` is already a dependency (from the 1.1 scaffold). Test client classes `WebSocketStompClient` + `StandardWebSocketClient` are on the classpath — use them for the live-push integration test. No new backend dependency required.
- **Jackson `ObjectMapper`:** Story 1.5 found there is no auto-configured `ObjectMapper` bean in this context. For REST, Spring MVC's `MappingJackson2HttpMessageConverter` configures its own Jackson — JSON responses work without an `ObjectMapper` bean. If a test or component needs to inject `ObjectMapper`, construct one (as the agent codec does) rather than autowiring. [Source: 1-5-agent-runtime-redis-streams-plumbing.md]
- **Frontend STOMP**: `@stomp/stompjs` 7.3.0 (verified). It works over native WebSocket (`ws://`), no SockJS needed for a same-origin/dev setup.

### Architecture requirements (mandatory)
- **Decision 6 — API & Communication:** REST (JSON) request/response + WebSocket (STOMP over Spring WebSocket) for live push; **errors via RFC 9457 Problem Details**; springdoc-openapi for personal API reference. [Source: architecture.md#Decision 6]
- **Format/Naming:** success returns the resource directly (**no envelope**); RFC 9457 for every error; REST paths **plural, kebab-case**, `/api/...`, **no version prefix** for MVP. JSON camelCase (Java records → Jackson emits camelCase). [Source: architecture.md#Format Patterns; #Naming Patterns]
- **Boundary:** frontend ↔ backend only via REST (`/api/...`) + STOMP WebSocket; the frontend never touches the DB/models directly. [Source: architecture.md#Architectural Boundaries]
- **Error handling:** backend `@RestControllerAdvice` → Problem Details. [Source: architecture.md#Process Patterns]

### Scope decisions (keep it a round-trip, not a feature)
- **springdoc-openapi (Swagger UI) is DEFERRED.** Decision 6 lists it, but it's "personal API reference," not part of this AC, and springdoc's Boot-4 compatibility needs its own version check (prior major-version surprises in this project: Boot 4, Spring AI 2.0, Testcontainers 2.x). Add it in a later small story once a Boot-4-compatible springdoc is confirmed. Note this in completion.
- **No browser e2e here.** AC #3's "received live" is proven by a real STOMP-over-WebSocket Java client (same protocol the browser uses). The visual frontend round-trip lands with the dashboard shell (Story 1.7), which consumes the `lib/` clients this story creates.
- The demo topic/endpoint are placeholders; real live data (prices, alerts, agent status) arrives with their features. The architecture's eventual pattern is Redis pub/sub → STOMP fan-out; this story just stands up the STOMP side with a direct `SimpMessagingTemplate` publish.

### Source tree this story touches
```
backend/src/main/java/com/argus/
├── common/
│   ├── SystemInfoController.java   # NEW  GET /api/system-info (typed record DTO)
│   ├── SystemInfo.java             # NEW  record DTO
│   ├── NotFoundException.java      # NEW  sample app exception
│   ├── GlobalExceptionHandler.java # NEW  @RestControllerAdvice -> ProblemDetail
│   └── LivePushService.java        # NEW  wraps SimpMessagingTemplate
├── config/
│   └── WebSocketConfig.java        # NEW  @EnableWebSocketMessageBroker (/ws, /topic, /app)
└── resources/application.yml       # UPDATE  spring.mvc.problemdetails.enabled: true (+ CORS if needed)
backend/src/test/java/com/argus/
├── common/SystemInfoControllerTest.java   # NEW  REST + problem+json
└── config/StompRoundTripIntegrationTest.java  # NEW  live push via WebSocketStompClient

frontend/src/lib/
├── apiClient.ts                    # NEW  typed fetch + RFC 9457 error parsing
└── wsClient.ts                     # NEW  @stomp/stompjs wrapper
frontend/package.json               # UPDATE  +@stomp/stompjs
```

### CORS
- The frontend dev server is `http://localhost:3000`; the backend is `:8080`. Add CORS for `/api/**` (allow `http://localhost:3000`) and set the STOMP endpoint's allowed origins. A simple `WebMvcConfigurer addCorsMappings` in `com.argus.config` is fine for dev. (On the Mini both are same-origin behind Tailscale; keep dev-permissive but not `*` with credentials.)

### Testing standards
- REST test: prefer a slice (`@WebMvcTest(SystemInfoController.class)` importing the advice) so it doesn't need the datasource/Docker. If a full `@SpringBootTest` is used instead, import `TestcontainersConfiguration` (datasource is active).
- STOMP test: needs the servlet container → `@SpringBootTest(webEnvironment = RANDOM_PORT)`; full context boots → `@Import(TestcontainersConfiguration.class)` (+ Docker), consistent with Stories 1.2/1.4/1.5. Use a `CountDownLatch`/`BlockingQueue` with a bounded await; no Awaitility.
- `./mvnw test` stays green across all suites.

### Project Structure Notes
- Prior stories 1.1/1.2 done; 1.4/1.5 committed (status review). Story 1.3 deferred (Mini). Continue on a new branch off `281db9f`.
- `com.argus.common` and `com.argus.config` currently hold only `package-info.java` — this story fills them (RFC 9457 advice + WebSocket config are exactly their stated responsibilities).
- This is the first frontend code since the 1.1 scaffold — keep the `lib/` clients minimal and framework-agnostic; the shell (1.7) wires them into React/TanStack Query/Zustand.

### References
- [Source: epics.md#Story 1.6: REST + WebSocket round-trip] — user story + ACs.
- [Source: architecture.md#Decision 6 — API & Communication] — REST + STOMP + RFC 9457 + springdoc.
- [Source: architecture.md#Format Patterns / #Naming Patterns] — no envelope, problem details, kebab-case `/api`, no version prefix.
- [Source: architecture.md#Architectural Boundaries] — REST + STOMP is the only FE↔BE channel.
- [Source: 1-5-agent-runtime-redis-streams-plumbing.md] — no auto ObjectMapper bean; Testcontainers test pattern.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

**Session 2026-06-18. All 6 tasks complete; 15/15 tests + live curl verified.**

- **Cross-story regression caught by an existing test (important):** adding `@EnableWebSocketMessageBroker` registers a `messageBrokerTaskScheduler` (`TaskScheduler` bean). Boot's virtual-thread scheduler is `@ConditionalOnMissingBean(TaskScheduler)`, so it **backed off**, and `@Scheduled` agent polling silently fell back to the broker's **platform** threads (thread `MessageBroker-3`) — breaking Story 1.5's AC #2. The `AgentRuntimeIntegrationTest` virtual-thread assertion failed and surfaced it. Fix: declare an explicit virtual-thread `TaskScheduler` bean (`SimpleAsyncTaskScheduler` + `setVirtualThreads(true)`, named `taskScheduler`) in `AgentConfig`. Re-verified: agents run on `agent-scheduler-*`, `virtualThread=true`. **Lesson: enabling a Spring feature that contributes a `TaskScheduler`/`Executor` can hijack `@Scheduled`/`@Async`; pin our own.**
- **Boot 4 test-autoconfig package move:** `@WebMvcTest` is now `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (not `...test.autoconfigure.web.servlet`). `@LocalServerPort` unchanged (`org.springframework.boot.test.web.server`).
- **RFC 9457 works two ways:** the `@ExceptionHandler` returning `ProblemDetail` handles app exceptions; `spring.mvc.problemdetails.enabled=true` makes framework errors problem+json too.
- WS endpoint uses `setAllowedOriginPatterns("*")` so both the Java test client and a browser connect in dev; Tailscale is the real network boundary (platform never public).
- springdoc-openapi (Swagger) intentionally NOT added — deferred (needs a Boot-4-compatible springdoc; not in this AC). Noted in deferred-work.

### Completion Notes List

- ✅ **All 6 tasks complete; ACs #1–#5 satisfied.** REST (`/api/system-info`, typed record JSON, no envelope), RFC 9457 problem+json errors via a global advice, STOMP `/ws` + `/topic` with `LivePushService`, and frontend `lib/apiClient.ts` + `lib/wsClient.ts` typed clients. CORS for the dev frontend.
- **Tests: 15/15 green.** Web slice (typed JSON + problem+json), real STOMP-over-WebSocket round-trip (Testcontainers), all prior suites still pass.
- **Live verification:** `/api/system-info` → `{name,version,profile,time}` (camelCase, 200); `/api/system-info/nope` → RFC 9457 (404 problem+json).
- **Fixed a Story-1.5 regression** (virtual-thread scheduler hijacked by the STOMP broker scheduler) — see Debug Log.
- **Deferred:** browser end-to-end (lands with the 1.7 dashboard shell, which consumes these clients); springdoc Swagger.
- **Left clean:** backend + compose stopped. Nothing committed yet. **Status → review.**

### File List

_New (backend, relative to repo root):_
- `backend/src/main/java/com/argus/common/SystemInfo.java`
- `backend/src/main/java/com/argus/common/SystemInfoController.java`
- `backend/src/main/java/com/argus/common/NotFoundException.java`
- `backend/src/main/java/com/argus/common/GlobalExceptionHandler.java`
- `backend/src/main/java/com/argus/common/LivePushService.java`
- `backend/src/main/java/com/argus/config/WebSocketConfig.java`
- `backend/src/main/java/com/argus/config/CorsConfig.java`
- `backend/src/test/java/com/argus/common/SystemInfoControllerTest.java`
- `backend/src/test/java/com/argus/config/StompRoundTripIntegrationTest.java`

_New (frontend):_
- `frontend/src/lib/apiClient.ts`
- `frontend/src/lib/wsClient.ts`

_Modified:_
- `backend/src/main/resources/application.yml` (+`spring.mvc.problemdetails.enabled`)
- `backend/src/main/java/com/argus/agent/AgentConfig.java` (explicit virtual-thread `TaskScheduler` — regression fix)
- `frontend/package.json` / `package-lock.json` (+`@stomp/stompjs`)
- removed `frontend/src/lib/.gitkeep` (folder now has real files)

### Change Log

- 2026-06-18 — Story 1.6: REST `/api/system-info` (typed JSON) + RFC 9457 global error handler + STOMP `/ws`+`/topic` with live-push service + frontend typed apiClient/wsClient. Fixed a virtual-thread `@Scheduled` regression introduced by the STOMP broker scheduler. 15/15 tests green; live curl verified. Status → review.
