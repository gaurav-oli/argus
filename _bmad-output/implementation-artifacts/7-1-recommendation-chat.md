---
baseline_commit: 508dd9c
---

# Story 7.1: Recommendation chat

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to open a context-aware chat from a recommendation and ask questions about it,
so that I can interrogate a recommendation — grounded in its own signals, diagnostic, and my portfolio — before acting.

## Acceptance Criteria

1. **Grounded answer via the Model Gateway** — **Given** an existing recommendation, **When** I open Ask AI on that card and send a question, **Then** the backend assembles a context containing that recommendation's probabilities + the multi-signal diagnostic (each agent's direction, weight, rationale) + my current portfolio state (holdings/weights + health score), builds a single grounding prompt, and gets the answer from `ModelGateway.generate(prompt, ModelTier.BIG)` — **no LLM call is made outside the Model Gateway**. (FR-30)
2. **Conversational follow-ups** — **Given** an open chat with prior turns, **When** I ask a follow-up, **Then** the prior turns are included so the answer is coherent in context; the chat session persists in the UI until I dismiss the panel. (FR-30)
3. **REST contract** — A session-gated `POST /api/recommendations/{id}/chat` accepts `{ messages: [{role, content}, …] }` and returns `{ role: "assistant", content }`. Unknown/forbidden recommendation id → RFC 9457 Problem Details (404); not session-authenticated → 401. camelCase JSON (Jackson 3).
4. **Dev/Mac scope (mock model, no Ollama on the laptop)** — Under the `dev` profile the call resolves through the wired `MockChatModel` (canned response) so the full request → context-assembly → gateway → response path is exercised and tested **on the MacBook with no Ollama**. Real `gemma4:26b` generation and the ≤15s latency target are verified on the Mac Mini (added to `docs/mac-mini-validation.md`). (NFR ≤15s; A-9)
5. **Ask-AI panel UI** — The previously-disabled "Ask AI" button on a recommendation card opens a chat panel (`features/conversation`) showing the message thread, an input box, send affordance, and a "thinking / warming up" indicator while a response is pending (covers the Mini's ~28s cold-load). Premium styling via the existing CSS-var tokens + `motion` entrance/typing animation, honoring `useReducedMotion`. Closing the panel ends the session.
6. **Tests** — Backend unit: the context assembler includes the rec's signals + portfolio/health in the prompt; the service routes to `ModelTier.BIG` and returns the gateway's content; a stub/mock gateway drives a deterministic answer (no network). Integration (Testcontainers, `dev` profile → MockChatModel, mirror existing `recommendation`/`portfolio` integration tests): seed a recommendation → `POST /chat` → assert a `200` assistant message and that the endpoint is session-gated (`401` unauthenticated) and `404`s an unknown id. `./mvnw verify` green; frontend `npm run lint` + `npm run build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Context assembly** (AC: #1)
  - [x] New package `com.argus.conversation`. `RecommendationContextAssembler.assemble(rec, snapshot, health)` renders the grounding block. Kept pure (takes the already-loaded records) so it unit-tests with no mocks; `ConversationService` gathers the snapshot/health via `LivePortfolioService.currentSnapshot()` + `HealthScoreService.compute()` and the rec via `RecommendationService.diagnostic(id)`.
  - [x] Compact deterministic block: recommendation summary (direction + bull/bear/confidence %) + per-agent signal lines (direction, weight, rationale) + portfolio holdings/weights + health score & deduction reasons. Null-safe ("n/a").
- [x] **Task 2 — Conversation service + prompt** (AC: #1, #2)
  - [x] `ConversationService.askAboutRecommendation(rec, messages)`: system framing ("answer only from provided context; cite, don't invent numbers; not financial advice") + grounding block + prior turns + `Assistant:` → `modelGateway.generate(prompt, ModelTier.BIG)`. Constructor-injected gateway (mirrors `SentimentAnalyzer`).
  - [x] Stateless follow-ups: client sends full `messages`; history capped at `MAX_TURNS = 20`.
- [x] **Task 3 — REST endpoint** (AC: #3)
  - [x] `ConversationController` (in `com.argus.conversation`, `@RequestMapping("/api/recommendations")`) `POST /{id}/chat`. `ChatRequest(List<ChatMessage>)`, `ChatMessage(role, content)`; returns `ChatMessage("assistant", …)`. Unknown id → `ResponseStatusException(NOT_FOUND)` (RFC 9457); session-gated by the existing `SessionAuthFilter`; camelCase.
- [x] **Task 4 — Frontend Ask-AI panel** (AC: #5, #2)
  - [x] `lib/apiClient.ts`: `ChatMessage` type + `sendRecommendationChat(id, messages)` via `apiPost`.
  - [x] `features/conversation/RecommendationChat.tsx`: client component, message list + textarea (Enter to send), animated "Thinking — warming up…" indicator, `motion` entrance + `useReducedMotion`, Escape/backdrop close, CSS-var tokens. Centered-modal variant of the fixed-overlay dialog pattern.
  - [x] Enabled the previously-disabled "Ask AI" button in `RecommendationCards.tsx` (now on every card, available regardless of decided state per FR-30); opens the panel for that card's id/ticker.
- [x] **Task 5 — Tests + verify** (AC: #6)
  - [x] Unit: `RecommendationContextAssemblerTest` (block contains signal rationales + holdings + health), `ConversationServiceTest` (routes `ModelTier.BIG`, returns gateway content, includes prior turns; mocked gateway/portfolio/health).
  - [x] Integration: `ConversationIntegrationTest` (`@ActiveProfiles("dev")` → real `MockChatModel`, Testcontainers): pin/login, seed a rec, `POST /chat` → 200 assistant + non-empty content; 401 unauthenticated; 404 unknown id.
  - [x] `./mvnw verify` green (284 tests, 0 failures); `npm run lint` + `npm run build` clean.
- [x] **Task 6 — Mac Mini deferral** (AC: #4)
  - [x] Added §5 to `docs/mac-mini-validation.md` (grounded answer + ≤15s warm / ~28s cold "warming up" + multi-turn, the first real backend→Ollama call). Logged streaming/pre-warm, persistence, and the Haiku-stub in `deferred-work.md`.

### Review Findings (code-review 2026-06-23)

Adversarial 3-layer review (Blind Hunter, Edge Case Hunter, Acceptance Auditor, Opus 4.8). All 6 ACs + all 3 Decisions verified satisfied; no scope creep. Triage: 1 decision-needed, 5 patch, 2 deferred, 4 dismissed.

- [x] [Review][Defer] Model failure surfaces as HTTP 200 carrying the Haiku-stub text — On a real model/Ollama failure (timeout, cold-load fail, OOM), `DefaultModelGateway.generateBig` catches the exception and returns `StubHaikuFallback` text (`[haiku-fallback-stub] not implemented yet`); `ConversationController` returns it as a 200 assistant message, so the frontend error/retry path never engages. Pre-existing gateway behavior (Story 1.4), first surfaced by 7.1. Cannot occur under the dev mock. **Deferred to Story 7.3** (real Haiku fallback replaces the stub; gateway should also turn a true fallback-failure into a 503 rather than success). [blind+edge, High] [backend/.../model/DefaultModelGateway.java:64; conversation/ConversationController.java]
- [x] [Review][Patch] Reject empty/blank chat thread before invoking the BIG model — `ConversationController.validate` 400s an empty thread or blank trailing question. [backend/.../conversation/ConversationController.java]
- [x] [Review][Patch] Cap per-message / total content size — 413 above `MAX_MESSAGE_CHARS=4000` / `MAX_TOTAL_CHARS=16000`; frontend textarea `maxLength`. [backend ConversationController.java; frontend RecommendationChat.tsx]
- [x] [Review][Patch] Validate message roles / require the last turn be a user turn — roles must be user/assistant; closes the fabricated-assistant-turn seam. [backend/.../conversation/ConversationController.java]
- [x] [Review][Patch] Abort the in-flight request on panel close/unmount (AbortController) + guard state updates — `abortRef` aborted in the unmount cleanup; aborted requests update no state. [frontend RecommendationChat.tsx; apiClient.ts signal plumbed through `apiPost`/`sendRecommendationChat`]
- [x] [Review][Patch] On error, drop the unanswered user turn and surface the ApiError detail — failed turn removed, question restored to the box, RFC 9457 `detail` shown. [frontend RecommendationChat.tsx]
- [x] [Review][Defer] No client-side timeout / queued-vs-generating feedback on the concurrency-1 BIG gateway [frontend RecommendationChat.tsx] — deferred, ties to the deferred token-streaming + pre-warm work (Mini-relevant)
- [x] [Review][Defer] Server-side `MAX_TURNS` truncation is silent — long sessions drop oldest turns with no UI signal [backend ConversationService.java] — deferred, minor UX
- Dismissed (4): warming-up indicator only exercisable on the Mini (as-specified, AC #4 / mac-mini §5); unguarded `getDirection()`/`getStatus()` NPE (false positive — both are NOT-NULL/derived columns); frontend `role` union widening (latent — controller hardcodes "assistant"); raw prompt-delimiter injection (out of the single-user, session-gated, local-only threat model).

## Dev Notes

### ⭐ Decisions to confirm (recommended defaults chosen so dev can proceed)
1. **Conversation persistence — recommended: stateless server, client holds history (NO DB).** The client sends the full `messages` array each turn; nothing is persisted. Matches FR-30 "session persists until Gaurav dismisses the chat panel" (a client-side session), keeps the endpoint simple, and **adds no Flyway migration** (next free version stays **V20**). *(vs. persisting threads to Postgres — unnecessary for a single user and out of this story's ACs.)*
2. **Streaming — recommended: non-streaming REST for the MVP.** The dev `MockChatModel` returns a full string instantly, so token streaming adds no laptop-testable value; the UI shows a "thinking/warming up" indicator instead. Architecture's token-streaming + pre-warm latency mitigation (and a pre-warm-on-panel-open trigger) is a **follow-up that only matters with the real model — validate/needed on the Mini.** Note it; don't build it here.
3. **Grounding scope — recommended: rec diagnostic + current portfolio snapshot + health score.** This is exactly FR-30's "full context" minus the calendar (calendar grounding is portfolio-chat territory, Story 7.2). Keep the block compact and deterministic.

### Scope boundaries (don't over-build — these are later Epic 7 stories)
- **Portfolio (freeform/dashboard) chat** with holdings + health + **calendar** + recent recs → **Story 7.2**. 7.1 is per-recommendation only.
- **Haiku escalation / "Get deeper analysis"** (FR-32) → **Story 7.3**. 7.1 uses `ModelTier.BIG` (local) only. Note: `HaikuFallback` is still a stub (`[haiku-fallback-stub] not implemented yet`) — BIG-tier auto-fallback on a local failure will hit that stub; acceptable here, real Haiku lands in 7.3.
- **4 personas / "Get Perspectives"** (FR-33/34) → **Stories 7.4/7.5**.
- **Token streaming + pre-warm** → deferred (Decision 2; Mini-relevant).
- **Conversation persistence / history search** → out (Decision 1).

### Reuse — read these before writing anything (prevents reinvention)
- `model/ModelGateway.java` — call `generate(prompt, ModelTier.BIG)` (Ask AI is an explicit BIG-tier caller). BIG is serialized at concurrency 1 with automatic Haiku fallback; **never instantiate a `ChatClient`/`ChatModel` directly — always go through the gateway.** Mirror the injection in `intelligence/SentimentAnalyzer.java`.
- `recommendation/RecommendationService.java` → `diagnostic(id)` returns the `RecommendationCard` with eager `SignalView[]` (`agent, direction, weight, rationale`) + probabilities/confidence. This IS the grounding audit trail — do not recompute signals.
- `recommendation/RecommendationController.java` — existing session-gated `/api/recommendations` controller (has `GET /{id}`, `POST /{id}/decision`); add the chat endpoint here or in a sibling `ConversationController` on the same base path.
- `portfolio/LivePortfolioService.java` → `currentSnapshot()` (holdings, weights, P&L, CAD) and `portfolio/HealthScoreService.java` → `compute()` (0–100 score + deduction reasons, rule-based, no LLM) for portfolio grounding.
- Frontend: `features/recommendations/RecommendationCards.tsx` (the disabled "Ask AI" button + the card data/`ForecastCard` pattern), `lib/apiClient.ts` (`apiPost`, `ApiError`/RFC 9457, `credentials:"include"`), `components/ui/MotionCard.tsx` + `AnimatedNumber.tsx` (motion patterns), `components/portfolio/HealthScoreBreakdown.tsx` (fixed-overlay dialog pattern), `app/globals.css` tokens.

### Architecture / convention guardrails (mandatory)
- **Single LLM home:** all model access via `com.argus.model` Model Gateway; Ask-AI context assembly lives in `com.argus.conversation`. Frontend `features/conversation`. [architecture.md#Decision 1, #Requirements→Structure Mapping (F10 Ask AI → conversation, model)]
- **REST + RFC 9457:** session-gated under `/api/...`, resource returned directly, Problem Details on error, camelCase (Jackson 3, `tools.jackson`). Frontend ↔ backend only via REST/STOMP — never touch the model directly. [architecture.md#Decision 6, #API boundary]
- **No LLM-invented numbers:** the chat answers *about* signals/probabilities but must not fabricate new probabilities — the grounding block carries the engine's numbers; the prompt instructs the model to answer only from provided context. [PRD#FR-30; recommendation engine is non-LLM, GAP-6]
- **Privacy seam (for later):** 7.1 is local-only so full context is fine; do NOT send raw positions to any external API. When 7.3 adds Haiku, only sanitized context may leave the network. [architecture.md#Privacy]
- **No new DB** (Decision 1): no migration; next free Flyway version remains **V20**.
- **Tests:** JUnit Jupiter (no AssertJ); mock the `ModelGateway` at the boundary for unit tests and rely on `dev`-profile `MockChatModel` for the integration slice (no Ollama, no network); Testcontainers for the integration DB; auth via `/api/auth/pin` + `/api/auth/login`. Mirror `RecommendationControllerIntegrationTest` / `NewsSentimentIntegrationTest` (which mocks the gateway).

### Files to touch
- **New (backend):** `conversation/ConversationService.java`, `conversation/RecommendationContextAssembler.java`, `conversation/ChatRequest.java` + `conversation/ChatMessage.java` (records), a chat endpoint (in `RecommendationController` or new `conversation/ConversationController.java`); tests `conversation/RecommendationContextAssemblerTest.java`, `conversation/ConversationServiceTest.java`, `conversation/ConversationIntegrationTest.java`.
- **New (frontend):** `src/features/conversation/RecommendationChat.tsx`.
- **Modified (frontend):** `src/lib/apiClient.ts` (chat types + `sendRecommendationChat`), `src/features/recommendations/RecommendationCards.tsx` (enable the Ask AI button, open the panel).
- **Modified (docs):** `docs/mac-mini-validation.md` (Story 7.1 live-model item), `_bmad-output/implementation-artifacts/deferred-work.md` (any laptop deferrals).

### Latest tech notes
- **Model:** validated big model is **`gemma4:26b`** via Ollama, `ARGUS_MODEL_KEEP_ALIVE=5m` (unload-when-idle), bound in `application-prod.yml` (`ARGUS_BIG_MODEL`). Warm first-token <1s, ~22 tok/s → comfortably ≤15s warm; **cold-load ~28s** → the UI "warming up" state (AC #5) exists for this. The `dev` profile excludes Ollama autoconfig and injects `MockChatModel` (canned `dev-response`). [docs/mac-mini-validation.md §1; backend `application-dev.yml`/`application-prod.yml`]
- **Spring AI 2.0 `ChatClient`** is already wrapped inside `DefaultModelGateway` — you only touch `ModelGateway`.

### Project Structure Notes
New `com.argus.conversation` package (Ask-AI context assembly) and `features/conversation` frontend folder are exactly the slots reserved in `architecture.md#Backend package structure` / `#Frontend feature structure` — no structure variance. No migration. Reuses recommendation + portfolio + model packages without modifying their domain.

### References
- [Source: epics.md#Epic 7 / Story 7.1] — story + BDD AC (grounded in the rec's signals, diagnostic, and portfolio, via the Model Gateway, within the latency target).
- [Source: prd.md#FR-30] — Recommendation Chat Mode: full context (Probability Forecast Card + Multi-Signal Diagnostic + agent outputs at recommendation time + current portfolio), ≤15s, session persists until dismissed.
- [Source: prd.md#§13 NFR] — Ask AI response time ≤15s; [A-9] Gemma 4 26B MoE within the M3 envelope.
- [Source: architecture.md#Decision 1 (Model Gateway/governance), #Decision 6 (REST+STOMP, RFC 9457), #Requirements→Structure Mapping (F10 Ask AI → conversation+model / features/conversation), #Privacy].
- [Source: docs/mac-mini-validation.md §1 (gemma4:26b, keep-alive 5m, ~28s cold-load → "warming up" is Epic 7), §2 (first real backend→Ollama call awaits the Ask-AI endpoint)].
- [Source: model/ModelGateway.java, model/DefaultModelGateway.java, intelligence/SentimentAnalyzer.java (injection pattern), recommendation/RecommendationService#diagnostic, portfolio/LivePortfolioService#currentSnapshot, portfolio/HealthScoreService#compute].
- [Source: frontend lib/apiClient.ts, features/recommendations/RecommendationCards.tsx (disabled Ask AI button), components/portfolio/HealthScoreBreakdown.tsx (dialog pattern), components/ui/MotionCard.tsx (motion)].

## Open Questions (for the user)
1. **Conversation persistence:** default is stateless / client-holds-history (no DB). Confirm you don't want server-side saved chat threads for 7.1.
2. **Streaming/pre-warm:** default defers token streaming + pre-warm to a Mini-side follow-up (mock returns instantly on the laptop). Confirm a non-streaming MVP with a "warming up" indicator is acceptable.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 284 tests, 0 failures** (+7 vs the prior 277: assembler 2, service 2, integration 3).
- `npm run lint` clean; `npm run build` (Next 16 / Turbopack) clean, TypeScript clean.
- Decisions applied (story Open Questions, confirmed by user "go ahead"): stateless/no-DB conversation; non-streaming MVP with a "warming up" indicator.

### Completion Notes List

- **Stateless, grounded, single gateway:** `ConversationController.POST /api/recommendations/{id}/chat` → `RecommendationService.diagnostic(id)` (404 if absent) → `ConversationService` assembles the grounding block (`RecommendationContextAssembler`, fed by `LivePortfolioService.currentSnapshot()` + `HealthScoreService.compute()`) → `ModelGateway.generate(prompt, ModelTier.BIG)`. No LLM call exists outside the gateway. The client resends the full thread each turn; nothing persisted → **no migration, V20 stays the next free Flyway version.**
- **Pure assembler:** renders rec probabilities/signals + holdings/weights + health deductions as deterministic, null-safe text; the prompt instructs the model to cite, never invent, numbers (consistent with the non-LLM scoring engine, GAP-6).
- **Laptop scope honored:** the integration test runs under `@ActiveProfiles("dev")` so the real in-memory `MockChatModel` answers (no Ollama) — the full request→assembly→gateway→response path is exercised here. Real `gemma4:26b` grounding + ≤15s-warm / ~28s-cold "warming up" verification is deferred to the Mini (`docs/mac-mini-validation.md` §5) — this is the first real backend→Ollama call.
- **Frontend:** `features/conversation/RecommendationChat.tsx` — premium centered-modal chat (motion entrance, reduced-motion aware, animated "warming up" indicator, Enter-to-send, Escape/backdrop close). The "Ask AI" button is now enabled on every recommendation card (per FR-30, regardless of Taken/Declined state).
- **Scope deferred** (logged in `deferred-work.md`): token streaming + pre-warm (Mini-relevant only), server-side persistence, real Haiku escalation (7.3 — `HaikuFallback` is still a stub).

### File List

**New (backend):** `conversation/ChatMessage.java`, `conversation/ChatRequest.java`, `conversation/RecommendationContextAssembler.java`, `conversation/ConversationService.java`, `conversation/ConversationController.java`
**New (backend tests):** `conversation/RecommendationContextAssemblerTest.java`, `conversation/ConversationServiceTest.java`, `conversation/ConversationIntegrationTest.java`
**New (frontend):** `frontend/src/features/conversation/RecommendationChat.tsx`
**Modified (frontend):** `frontend/src/lib/apiClient.ts` (`ChatMessage` + `sendRecommendationChat`), `frontend/src/features/recommendations/RecommendationCards.tsx` (enable Ask AI button, render chat panel)
**Modified (docs):** `docs/mac-mini-validation.md` (§5 live-model item), `_bmad-output/implementation-artifacts/deferred-work.md` (7.1 deferrals)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-23 | Story created (create-story workflow). First story of Epic 7 (Conversational AI). Per-recommendation Ask AI: `com.argus.conversation` context assembly (rec diagnostic + portfolio/health) → `ModelGateway.generate(BIG)`; session-gated `POST /api/recommendations/{id}/chat`; `features/conversation` chat panel wired to the existing disabled Ask-AI button. Laptop scope: dev `MockChatModel`, full path tested without Ollama; real `gemma4:26b` ≤15s verification deferred to the Mac Mini. No migration (V20 stays free). Portfolio chat → 7.2, Haiku escalation → 7.3, personas → 7.4/7.5. Status → ready-for-dev. |
| 2026-06-23 | Implemented 7.1 (FR-30): stateless grounded recommendation chat through the Model Gateway (BIG tier) + premium Ask-AI panel; "Ask AI" enabled on every card. Decisions applied: no DB persistence, non-streaming MVP with "warming up" indicator. 284 backend tests (+7) green; frontend lint+build clean. Mini-only live-model verification logged. Status → review. |
| 2026-06-23 | Code review (adversarial 3-layer, Opus 4.8): all 6 ACs + 3 Decisions verified, no scope creep. 1 HIGH (model-failure → 200 stub text) deferred to 7.3 (pre-existing gateway behavior, can't occur on the laptop). 5 patches applied: request validation (empty/blank/roles → 400), content-size caps (413), AbortController on panel close, error-detail surfacing + failed-turn cleanup. 2 deferred (UI timeout/streaming, silent MAX_TURNS truncation), 4 dismissed. 287 backend tests (+3) green; frontend lint+build clean. Status → done. |
