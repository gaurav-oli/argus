---
baseline_commit: 72bbdb9
---

# Story 7.3: Haiku escalation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want complex Ask-AI questions escalated to Claude Haiku,
so that hard reasoning still works when the local model isn't enough — with the cost tracked and my raw portfolio never leaving the network.

## Acceptance Criteria

1. **Escalation routes to Claude Haiku via the Model Gateway** — **Given** an Ask-AI chat (recommendation 7.1 or portfolio 7.2), **When** escalation is triggered via the "Get deeper analysis" action, **Then** the request routes through the Model Gateway to **Claude Haiku** (not the local model) and the Haiku answer is returned. (FR-32, epic 7.3)
2. **Cost is recorded per escalation** — **Given** a completed Haiku call, **When** it returns, **Then** the cost is computed from the response's token usage (input $1 / output $5 per MTok) and recorded (model, input/output tokens, USD cost) — visible in logs and retrievable in-process. (epic 7.3 "the cost is recorded")
3. **Privacy: sanitized context only** — **Given** an escalation, **When** the prompt is built for Haiku, **Then** it contains **no raw positions** — exact share counts, exact CAD dollar values, and the portfolio total are withheld; relative weights %, tickers, directions, health score, signal rationales, calendar, and probabilities may remain. The local-model path (7.1/7.2) is unchanged (full context, stays on the network). (architecture#Privacy)
4. **Key-gated; real fallback replaces the stub (resolves the 7.1/7.2 HIGH)** — **Given** an Anthropic API key is configured, **Then** `HaikuFallback` performs a real Haiku call; **Given** no key (default dev/laptop), **Then** escalation is unavailable and surfaces as a clean error (not placeholder text). The on-**failure** BIG-tier fallback uses the same real Haiku when keyed; when unavailable, a model failure becomes an error response — **the gateway no longer returns `[haiku-fallback-stub]…` as a 200**.
5. **REST + UI** — The chat endpoints accept an escalation flag (`POST /api/recommendations/{id}/chat` and `/api/portfolio/chat`, body adds `deeper: true`); escalation-unavailable → `503` RFC 9457. The shared `ChatPanel` shows a "Get deeper analysis" affordance after a normal answer; escalated replies are tagged "via Claude Haiku". camelCase.
6. **Dev/Mac scope** — The escalation path is **laptop-testable**: unit/integration tests mock the Anthropic-backed `HaikuFallback` (no network) for the success path, and assert the no-key path returns 503 under the `dev` profile (no key). Cost math is unit-tested. `./mvnw verify` green; frontend `npm run lint` + `npm run build` clean. Real Haiku with a live key + the local-model **on-failure** fallback are confirmed on the Mini (added to `docs/mac-mini-validation.md`).

## Tasks / Subtasks

- [x] **Task 1 — Spring AI Anthropic dependency + config** (AC: #1, #4)
  - [x] Add `spring-ai-starter-model-anthropic` to `backend/pom.xml` (version via the existing `spring-ai-bom` 2.0.0 — do NOT pin a separate version).
  - [x] `application-prod.yml`: `spring.ai.anthropic.api-key: ${ANTHROPIC_API_KEY:}`, `spring.ai.anthropic.chat.options.model: ${ARGUS_HAIKU_MODEL:claude-haiku-4-5-20251001}`, `max-tokens` (Anthropic requires it; e.g. 1024), temperature. Add `ANTHROPIC_API_KEY` to `.env.example`. Do NOT bind a key in `dev`.
  - [x] **Avoid the two-`ChatModel` ambiguity** that would break `DefaultModelGateway(ChatModel, …)`: prefer constructing the Anthropic client **manually in a key-gated `@Bean`** (so no competing autoconfig `ChatModel` bean exists), OR mark the local model `@Primary` and `@Qualifier("anthropicChatModel")` the Anthropic one. **Verify the Spring AI 2.0 Anthropic builder/property API against the installed jar — names may differ from training data** (the `claude-api` skill files are currently missing on disk; rely on the facts in this story + the Spring AI reference).
- [x] **Task 2 — Real Haiku fallback + cost recording** (AC: #1, #2, #4)
  - [x] Replace `StubHaikuFallback` with `AnthropicHaikuFallback implements HaikuFallback`, **`@ConditionalOnProperty("spring.ai.anthropic.api-key")`** (only wired when a key is set). It calls Haiku via the Anthropic `ChatClient`/`ChatModel`, then records cost from `ChatResponse.getMetadata().getUsage()` (`getPromptTokens()`/`getCompletionTokens()`).
  - [x] `UnavailableHaikuFallback implements HaikuFallback` (`@ConditionalOnMissingBean`-style fallback / the no-key branch): throws `ModelGatewayException("Deeper analysis is unavailable (no Anthropic API key)")`. **One `HaikuFallback` bean always exists** so `DefaultModelGateway` wires.
  - [x] `CostRecorder` in `com.argus.cost` (the reserved package, currently empty): `record(model, inputTokens, outputTokens)` → cost = `in×$1/1e6 + out×$5/1e6` (rates as constants/config), structured-log it (`event=haiku_escalation, cost=…`), keep an in-memory running total + last-call accessor for tests. **No persistence / no per-agent-month reset** — that's Epic 10.5 (Decision 2).
- [x] **Task 3 — Gateway escalation path + HIGH fix** (AC: #1, #4)
  - [x] `ModelGateway.escalate(String prompt)`: routes **directly** to `haikuFallback.generate(prompt)` (no local model, not on the BIG semaphore — Haiku is a separate remote resource). Throws `ModelGatewayException` when unavailable.
  - [x] Keep the existing on-failure BIG fallback calling `haikuFallback.generate(...)` — now real (or throwing when unavailable). Map `ModelGatewayException` → **503 Problem Details** via the common `@RestControllerAdvice` so a model/escalation failure is a clean error, not stub text as 200 (**resolves the 7.1/7.2 deferred HIGH**).
- [x] **Task 4 — Sanitized context + service escalation** (AC: #1, #3)
  - [x] Add a `boolean sanitized` parameter to `RecommendationContextAssembler.assemble(...)` and `PortfolioContextAssembler.assemble(...)`: when true, omit exact CAD dollar values (`cad(...)` amounts), the portfolio total line, and any share counts; KEEP tickers, weight %, directions, health score, signal rationales, calendar, probabilities, profile.
  - [x] `ConversationService`: `askAboutRecommendation(rec, messages, boolean deeper)` and `askAboutPortfolio(messages, boolean deeper)`. When `deeper`: build the grounding with `sanitized=true` and call `modelGateway.escalate(prompt)`; else unchanged (`generate(prompt, BIG)`, full context). (Keep the existing 2-arg methods delegating with `deeper=false` to avoid breaking call sites, or update call sites.)
- [x] **Task 5 — REST flag + frontend deeper-analysis** (AC: #5)
  - [x] `ChatRequest` gains `boolean deeper` (default false). `ConversationController` + `PortfolioChatController` pass `request.deeper()` to the service. (Validation via `ChatValidation` unchanged.)
  - [x] `apiClient`: `sendRecommendationChat`/`sendPortfolioChat` gain an optional `deeper` arg → body `{ messages, deeper }`.
  - [x] `ChatPanel`: `sendFn` signature gains `deeper`; after a normal assistant reply show a subtle "✦ Get deeper analysis" button that re-sends the **last user question** with `deeper=true`; tag the resulting bubble "via Claude Haiku". On a 503 show the `ApiError` detail ("Deeper analysis is unavailable…"). `RecommendationChat`/`PortfolioChat` wrappers thread `deeper` through their `sendFn`.
- [x] **Task 6 — Tests + verify** (AC: #6)
  - [x] Unit: `CostRecorderTest` (cost math: 1000 in + 500 out → $0.0035); `AnthropicHaikuFallbackTest` or a `ConversationServiceTest` escalation case with a **mocked `HaikuFallback`** (escalate → routes to Haiku, returns its text, sanitized prompt omits dollar values/totals, cost recorded). Assert sanitized assembler output excludes `CAD ` dollar lines but keeps weights/health.
  - [x] Integration (`@ActiveProfiles("dev")`, no key): `POST …/chat` with `deeper:true` → **503** (UnavailableHaikuFallback). Optionally a `@MockitoBean HaikuFallback` test for the 200 escalated path. Existing 7.1/7.2 tests stay green.
  - [x] `./mvnw verify` green; frontend `npm run lint` + `npm run build` clean.
- [x] **Task 7 — Mac Mini + docs** (AC: #6)
  - [x] `docs/mac-mini-validation.md`: add a 7.3 item — with a real `ANTHROPIC_API_KEY`, "Get deeper analysis" returns a real Haiku answer and cost is logged; and the **local-model on-failure fallback** (gemma4:26b fails → Haiku) works on the Mini. Update `deferred-work.md` (close the 7.1/7.2 Haiku-stub HIGH; note auto-detection + budget thresholds deferred).

### Review Findings (code-review 2026-06-23)

Adversarial 3-layer review (Blind Hunter, Edge Case Hunter, Acceptance Auditor, Opus 4.8). All 6 ACs + all 4 Decisions verified; no scope creep; the 7.1/7.2 Haiku-stub HIGH genuinely resolved. The two Blind "High"s were **false positives** (verified in source): the assemblers never emit share counts, and `cad()` always prefixes `"CAD "` so the dollar-suppression test is valid. Triage: 5 patch, 2 deferred, 6 dismissed.

- [x] [Review][Patch] `AnthropicHaikuFallback` now guards the full chain — null response → `ModelGatewayException`; null result/output → a friendly "couldn't produce a deeper answer" message (cost still recorded), no NPE. [edge, Med] [model/AnthropicHaikuFallback.java]
- [x] [Review][Patch] `recordCost(...)` warn-logs when usage metadata is missing before recording 0 (under-count now visible). [blind+edge, Med] [model/AnthropicHaikuFallback.java]
- [x] [Review][Patch] `CostRecorder.record` clamps token counts to ≥0. [edge, Low] [cost/CostRecorder.java]
- [x] [Review][Patch] Sanitization test now also asserts the escalated prompt omits the bare dollar figures (2600/6500/550). [blind, Low] [conversation/ConversationServiceTest.java]
- [x] [Review][Patch] "Get deeper analysis" is hidden while the input has unsent text (`canDeepen` also checks `!input.trim()`). [blind, Low] [features/conversation/ChatPanel.tsx]
- [x] [Review][Defer] `escalate` bypasses the big-model semaphore and has no per-call budget/concurrency gate — N concurrent escalations = N paid calls [model/DefaultModelGateway.java] — deferred, budget gating + auto-switch is the Cost Governor (Epic 10, FR-45/46); single-user UI guard (`pending`) bounds the common case
- [x] [Review][Defer] Sanitized context still sends tickers + company names + exact weights to Haiku — combined with public market caps a recipient could estimate sizes [conversation assemblers] — deferred, accepted posture (the hard barrier is dollar-value omission, which holds); drop names if a stricter bar is wanted later
- Dismissed (6): shares-leak (false positive — shares never emitted); dollar-test-vacuous (false positive — `cad()` emits `"CAD "`); stuck-pending-on-abort (not reachable — abort fires only on unmount since the 7.2 fix); `MAX_TURNS` truncation on the escalate path (already deferred, 7.2); `CostRecorder` double-vs-rounded precision (cosmetic, money path within tolerance); config placed in `application.yml` vs the spec's `application-prod.yml` (functionally correct — base applies to all profiles, key defaults blank).

## Dev Notes

### ⭐ Decisions to confirm (recommended defaults chosen)
1. **Escalation trigger — recommended: user-triggered "Get deeper analysis" only for now; auto-detection deferred.** The AC is satisfied by "via deeper analysis". Model-decided auto-detection risks false triggers + cost; defer it (a simple heuristic can come later). *(vs. building complexity auto-detection now.)*
2. **Cost recording — recommended: minimal in-memory `CostRecorder` + structured log, NO DB.** Epic 10.5 owns real persisted per-call/agent/model tracking + the monthly reset, and Epic 10.6 owns the 70/80/95% thresholds + auto-switch. 7.3 must only "record the cost". **No migration; V20 stays free.** *(vs. a cost table now — pre-empts Epic 10's schema.)*
3. **Sanitization — recommended: withhold exact $ amounts + share counts + portfolio total; keep weights/tickers/directions/health/probabilities.** "Raw positions" = sizes/dollar values; public tickers + relative weights are acceptable analysis context. Implement via a `sanitized` flag on the assemblers.
4. **Anthropic wiring — recommended: key-gated manual bean construction** (no competing autoconfig `ChatModel`), so `DefaultModelGateway`'s single-`ChatModel` injection is untouched and dev (no key) builds no Anthropic bean.

### Scope boundaries (don't over-build)
- **Budget thresholds (70/80/95%), notifications, auto-switch, monthly reset, persisted per-agent/model spend** → **Epic 10 (Stories 10.5/10.6)** + the Cost Governor. 7.3 records per-call cost only.
- **Auto-detection of complexity** → deferred (Decision 1).
- **The API budget panel / ops dashboard** (where spend is shown) → Epic 9/10. 7.3 records server-side + tags escalated replies in the chat; no dashboard.
- **Personas / Canadian lens** → 7.4/7.5.
- **Streaming + pre-warm** → still deferred (Mini-relevant).

### Reuse — read these first
- `model/ModelGateway.java`, `model/DefaultModelGateway.java` (BIG serialized + `haikuFallback.generate` on failure; add `escalate`), `model/HaikuFallback.java`, `model/StubHaikuFallback.java` (REPLACE), `model/ModelConfig.java` (bean wiring per profile), `model/ModelGatewayException.java`.
- `conversation/ConversationService.java` (add `deeper`), `conversation/RecommendationContextAssembler.java` + `conversation/PortfolioContextAssembler.java` (add `sanitized`), `conversation/ConversationController.java` + `conversation/PortfolioChatController.java` + `conversation/ChatRequest.java` (add `deeper`), `conversation/ChatValidation.java` (unchanged).
- `common/` RFC 9457 `@RestControllerAdvice` (add `ModelGatewayException` → 503).
- Frontend `features/conversation/ChatPanel.tsx` (deeper affordance + tag), `RecommendationChat.tsx`/`PortfolioChat.tsx` (thread `deeper`), `lib/apiClient.ts` (`deeper` arg).
- `com.argus.cost/package-info.java` (reserved package — put `CostRecorder` here).

### Architecture / convention guardrails (mandatory)
- **Single LLM home:** Haiku access only through the Model Gateway / `HaikuFallback`; never call Anthropic from `conversation` or a controller directly. [architecture#Decision 1]
- **Privacy:** sanitized context only to Haiku — never raw positions. The sanitization is the one new privacy-critical path; test it. [architecture#Privacy, line 34]
- **REST + RFC 9457:** `deeper` flag on existing endpoints; `ModelGatewayException` → 503 Problem Details; camelCase. [architecture#Decision 6]
- **Cost governance is woven through every cloud call:** record cost on every Haiku completion (both escalate and on-failure fallback paths run through `AnthropicHaikuFallback`, so recording there covers both). [architecture cross-cutting, structured-log `cost` field]
- **Tests:** JUnit Jupiter; mock `HaikuFallback`/the Anthropic client — **no live Anthropic calls in tests**; `dev` profile (no key) for the 503 path; Testcontainers for the integration slice. Bleeding-edge stack (Spring AI 2.0 / Boot 4 / Java 25) — verify the Anthropic API surface against the installed jar.

### Files to touch
- **New (backend):** `model/AnthropicHaikuFallback.java`, `model/UnavailableHaikuFallback.java` (+ a `HaikuConfig`/bean method for the key-gated Anthropic client), `cost/CostRecorder.java`; tests `cost/CostRecorderTest.java`, escalation tests in `conversation/` + `model/`.
- **Deleted (backend):** `model/StubHaikuFallback.java` (replaced).
- **Modified (backend):** `pom.xml`, `application-prod.yml`, `.env.example`, `model/ModelGateway.java` + `DefaultModelGateway.java` (add `escalate`), `model/ModelConfig.java` (wiring), the RFC 9457 advice (503 mapping), `conversation/ConversationService.java`, `RecommendationContextAssembler.java`, `PortfolioContextAssembler.java`, `ConversationController.java`, `PortfolioChatController.java`, `ChatRequest.java`, and the affected tests.
- **Modified (frontend):** `lib/apiClient.ts`, `features/conversation/ChatPanel.tsx`, `RecommendationChat.tsx`, `PortfolioChat.tsx`.
- **Modified (docs):** `docs/mac-mini-validation.md`, `_bmad-output/implementation-artifacts/deferred-work.md`.

### Latest tech notes (authoritative — verified against Anthropic + Spring AI docs; `claude-api` skill files are missing on disk)
- **Haiku model id:** `claude-haiku-4-5-20251001` (alias `claude-haiku-4-5`). 200k context, 64k max output; **`max-tokens` is required** by the Anthropic API — set `spring.ai.anthropic.chat.options.max-tokens`.
- **Pricing:** input **$1.00 / MTok**, output **$5.00 / MTok**. Cost = `inputTokens × 1e-6 + outputTokens × 5e-6` USD. The epic's "~$0.001–0.005/escalation" is optimistic for small calls; compute from real usage, don't hardcode.
- **Usage:** `ChatResponse.getMetadata().getUsage()` → `getPromptTokens()`, `getCompletionTokens()`, `getTotalTokens()`.
- **Spring AI:** starter `org.springframework.ai:spring-ai-starter-model-anthropic` (managed by `spring-ai-bom` 2.0.0). Props `spring.ai.anthropic.api-key`, `spring.ai.anthropic.chat.options.model|max-tokens|temperature`. With both Ollama + Anthropic starters present, each autoconfigures a `ChatModel` (`ollamaChatModel`/`anthropicChatModel`) → ambiguity; resolve per Decision 4.

### Project Structure Notes
New code stays in `com.argus.model` (gateway/fallback) and the reserved `com.argus.cost` (CostRecorder); conversation/frontend changes are additive. No structure variance; no migration (V20 stays free).

### References
- [Source: epics.md#Story 7.3] — complex query (auto-detected or "deeper analysis") → Model Gateway routes to Claude Haiku AND cost recorded.
- [Source: prd.md#FR-32] — escalation auto or "Get deeper analysis"; ~$0.001–0.005/call; tracked in budget panel; <10% of conversations.
- [Source: prd.md#FR-45/FR-46 + epics 10.5/10.6] — budget thresholds/auto-switch/persistence are **Epic 10**, not 7.3.
- [Source: architecture.md#Decision 1 (Model Gateway / Haiku fallback), #Privacy (sanitized context only), #cost package, #structured-log cost field].
- [Source: 7-1/7-2 stories + deferred-work.md] — conversation infra, shared `ChatValidation`/`ChatPanel`, and the deferred Haiku-stub HIGH this story closes.
- [Source: model/*, cost/package-info.java, backend/pom.xml (Spring AI 2.0.0), application-prod.yml].

## Open Questions (for the user)
1. **Auto-detection:** default implements only the user-triggered "Get deeper analysis"; model-decided auto-escalation is deferred. OK?
2. **Cost recording depth:** default is in-memory + structured log (no DB; persistence is Epic 10.5). OK, or do you want a persisted cost table now?
3. **Live Haiku on the laptop:** the escalation path is API-based and *can* be exercised on the MacBook with a real `ANTHROPIC_API_KEY`. Default keeps it key-gated and tests with a mock; say if you want to wire your key and live-test here rather than on the Mini.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 300 tests, 0 failures** (+6 vs 7.2's 294: CostRecorder 2, DefaultModelGateway +1 escalate, ConversationService +1 escalation/sanitize, +1 each conversation/portfolio integration 503).
- `npm run lint` clean; `npm run build` (Next 16) clean, TypeScript clean.
- **Bleeding-edge integration de-risked by jar inspection:** Spring AI 2.0's Anthropic chat autoconfig uses a mutually-exclusive selector (`spring.ai.model.chat`, `matchIfMissing=true`) and would try to build a client with no key → so it's pinned off (`=ollama`) and the Haiku client is built manually. Spring AI's `AnthropicChatModel.builder().anthropicClient(...)` needs a `com.anthropic.client.AnthropicClient`, but only `anthropic-java-core` ships transitively — added `anthropic-java-client-okhttp:2.40.1` for `AnthropicOkHttpClient`. `AnthropicChatOptions.model(...)` takes the SDK `Model` type → `Model.of("claude-haiku-4-5-20251001")`.

### Completion Notes List

- **Real Haiku, key-gated, single LLM home:** `HaikuConfig` builds `AnthropicHaikuFallback` (Spring AI `ChatClient` over a manually-built Anthropic model) only when `ANTHROPIC_API_KEY` is set; otherwise `UnavailableHaikuFallback` throws. `StubHaikuFallback` deleted. `ModelGateway.escalate(prompt)` routes "deeper analysis" straight to Haiku (no local model, no big-model semaphore).
- **HIGH resolved:** `ModelGatewayException → 503` (`GlobalExceptionHandler`); a model/escalation failure is a clean 503, never stub text as a 200. The on-failure BIG fallback also runs through the real/Unavailable Haiku now.
- **Cost recorded:** `CostRecorder` (`com.argus.cost`) computes USD from response token usage ($1 in / $5 out per MTok), structured-logs `event=haiku_escalation`, keeps an in-memory total (in-memory only — persistence/thresholds are Epic 10).
- **Privacy:** escalation grounds from **sanitized** context — `sanitized` flag on both assemblers omits exact CAD dollar amounts + share counts + portfolio total, keeping weights/tickers/health/probabilities. Local-model path unchanged (full context, stays local).
- **Frontend:** `ChatPanel` gained a "✦ Get deeper analysis (Claude Haiku)" action (re-answers the last question via the escalation flag) and tags escalated replies "via Claude Haiku"; 503 shows the Problem Details message. `deeper` threaded through `apiClient` + both wrappers.
- **Config:** `spring.ai.model.chat=ollama` (keeps local autoconfig, disables Anthropic autoconfig); `argus.haiku.*` (key-gated, empty key default). `ChatRequest.deeper` is a nullable `Boolean` (a primitive broke record deserialization → 400 on bodies without the flag).
- **Laptop scope:** escalation tested via mock / no-key 503 (`@ActiveProfiles("dev")`); real Haiku + on-failure fallback verified on the Mini (or laptop with a key) — `docs/mac-mini-validation.md` §7.

### File List

**New (backend):** `model/AnthropicHaikuFallback.java`, `model/UnavailableHaikuFallback.java`, `model/HaikuConfig.java`, `model/HaikuProperties.java`, `cost/CostRecorder.java`; tests `cost/CostRecorderTest.java`
**Deleted (backend):** `model/StubHaikuFallback.java`
**Modified (backend):** `pom.xml` (anthropic starter + okhttp client), `application.yml` (`spring.ai.model.chat`, `argus.haiku.*`), `model/ModelGateway.java` + `DefaultModelGateway.java` (`escalate`), `model/ModelGatewayException.java` (String ctor), `common/GlobalExceptionHandler.java` (503), `conversation/ChatRequest.java` (`deeper`), `conversation/ConversationService.java` (escalate + sanitize), `conversation/RecommendationContextAssembler.java` + `PortfolioContextAssembler.java` (`sanitized`), `conversation/ConversationController.java` + `PortfolioChatController.java` (pass deeper); tests `model/DefaultModelGatewayTest.java`, `conversation/ConversationServiceTest.java`, `conversation/ConversationIntegrationTest.java`, `conversation/PortfolioChatIntegrationTest.java`
**Modified (frontend):** `lib/apiClient.ts` (`deeper` arg), `features/conversation/ChatPanel.tsx` (deeper action + tag), `RecommendationChat.tsx` + `PortfolioChat.tsx` (thread deeper)
**Modified (docs):** `docs/mac-mini-validation.md` (§7), `_bmad-output/implementation-artifacts/deferred-work.md` (closed HIGH + 7.3 deferrals)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-23 | Code review (adversarial 3-layer, Opus 4.8): all 6 ACs + 4 Decisions verified; HIGH from 7.1/7.2 confirmed resolved; the two Blind "High"s were false positives (shares never emitted; `cad()` prefix valid). 5 patches applied: guard Haiku result chain, warn on null usage, clamp negative tokens, stronger sanitization test, hide deepen while typing. 2 deferred (escalation concurrency/budget → Epic 10; tickers/names in sanitized context), 6 dismissed. 300 backend tests green; frontend lint+build clean. Status → done. |
| 2026-06-23 | Implemented 7.3 (FR-32): real key-gated `AnthropicHaikuFallback` (Spring AI Anthropic + okhttp SDK, `claude-haiku-4-5-20251001`) replacing the stub; `ModelGateway.escalate`; `CostRecorder` ($1/$5 per MTok from usage); sanitized context for Haiku; `deeper` flag + "Get deeper analysis" in `ChatPanel`; `ModelGatewayException→503` (closed the 7.1/7.2 HIGH). Anthropic autoconfig pinned off (`spring.ai.model.chat=ollama`), client built manually. 300 backend tests (+6) green; frontend lint+build clean. Real-key/on-failure verification on the Mini (§7). Status → review. |
| 2026-06-23 | Story created (create-story workflow). Third story of Epic 7. Claude Haiku escalation (FR-32): real `AnthropicHaikuFallback` (Spring AI Anthropic, key-gated, `claude-haiku-4-5-20251001`) replacing the stub; `ModelGateway.escalate` path; `CostRecorder` in `com.argus.cost` (in-memory, $1/$5 per MTok from response usage); sanitized context for Haiku (no raw positions); `deeper` flag on the chat endpoints + a "Get deeper analysis" affordance in the shared `ChatPanel`; `ModelGatewayException`→503 (resolves the 7.1/7.2 Haiku-stub HIGH). Budget thresholds/persistence/auto-switch → Epic 10; auto-detection deferred. No migration (V20 stays free). Laptop-testable via a mocked Anthropic client; real key + on-failure fallback verified on the Mini. Status → ready-for-dev. |
