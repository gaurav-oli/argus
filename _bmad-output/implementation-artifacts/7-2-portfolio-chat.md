---
baseline_commit: 10b5c4f
---

# Story 7.2: Portfolio chat

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the user,
I want to open a freeform chat from the dashboard and ask about my whole portfolio,
so that I get holistic answers grounded in my holdings, health, upcoming events, and recent recommendations.

## Acceptance Criteria

1. **Grounded portfolio answer via the Model Gateway** — **Given** I'm on the dashboard, **When** I open Ask AI and ask a portfolio question (e.g. "What should I watch before the Fed meeting?", "Which holding concerns you most?"), **Then** the backend assembles a context containing **all holdings + weights** (portfolio snapshot), the **health-score breakdown** (score + deductions), **upcoming calendar events**, the **most recent recommendations**, and the **investor profile**, builds one grounding prompt, and gets the answer from `ModelGateway.generate(prompt, ModelTier.BIG)` — **no LLM call outside the Model Gateway**. (FR-31)
2. **Conversational follow-ups** — Prior turns are included so follow-ups are coherent; the chat session persists in the UI until I dismiss the panel. (FR-31)
3. **REST contract** — A session-gated `POST /api/portfolio/chat` accepts `{ messages: [{role, content}, …] }` and returns `{ role: "assistant", content }`. Empty/blank thread or bad role → 400; oversized content → 413; not authenticated → 401. camelCase JSON. (mirrors the 7.1 contract; no `{id}` — portfolio is global)
4. **Dev/Mac scope (mock model, no Ollama on the laptop)** — Under the `dev` profile the call resolves through the wired `MockChatModel`, so the full request → context-assembly → gateway → response path is tested **on the MacBook with no Ollama**. Real `gemma4:26b` generation + ≤15s latency verified on the Mac Mini (added to `docs/mac-mini-validation.md`). (NFR ≤15s)
5. **Dashboard Ask-AI launcher + panel** — A global "Ask AI" launcher in the dashboard chrome (TopBar) opens a portfolio chat panel: message thread, input, send, and a "thinking / warming up" indicator. Reuses the 7.1 chat UI (extracted shared `ChatPanel`), premium tokens + `motion` + `useReducedMotion`, Escape/backdrop close, AbortController on close. The recommendation chat (7.1) must keep working unchanged.
6. **Tests** — Backend unit: `PortfolioContextAssembler` includes holdings + health + calendar + recent recs + profile in the prompt; the service routes `ModelTier.BIG` and returns the gateway content (mock gateway/portfolio/health/calendar/recs). Integration (`dev` profile → MockChatModel, Testcontainers): seed positions + a recommendation + a calendar event → `POST /api/portfolio/chat` → 200 assistant; assert 401 unauthenticated and 400/413 validation. `./mvnw verify` green; frontend `npm run lint` + `npm run build` clean.

## Tasks / Subtasks

- [x] **Task 1 — Shared chat validation** (AC: #3)
  - [x] Extract the request validation from 7.1's `ConversationController` into a shared `com.argus.conversation.ChatValidation.validate(List<ChatMessage>)` static (empty → 400, role ∉ {user,assistant} → 400, per-message > `MAX_MESSAGE_CHARS=4000` / total > `MAX_TOTAL_CHARS=16000` → 413, trailing turn must be a non-blank user message). Update `ConversationController` to call it (no behavior change — existing 7.1 tests must stay green).
- [x] **Task 2 — Portfolio context assembler** (AC: #1)
  - [x] `PortfolioContextAssembler` (in `com.argus.conversation`): pure `assemble(snapshot, health, events, recentRecs, profile)` → deterministic grounding block: holdings (ticker, name, weight%, CAD value, P&L) + portfolio totals; health score + deductions (label + reason); upcoming calendar events (type, ticker, title, date, daysUntil, quiet-period); recent recommendations (one line each: ticker, direction, bull/bear %, confidence, status); investor profile. Null-safe. Cap list sizes (e.g. recent recs ≤ ~10, events from the next ~14 days) so the prompt stays bounded.
- [x] **Task 3 — Portfolio chat service path** (AC: #1, #2)
  - [x] `ConversationService.askAboutPortfolio(messages)`: gather `LivePortfolioService.currentSnapshot()`, `HealthScoreService.compute()`, upcoming events (`Agent7CalendarService` upcoming / `CalendarEventRepository.findByEventDateBetweenOrderByEventDateAsc(today, today+14)`), `RecommendationService.recent()` (cap), + the static investor profile → `PortfolioContextAssembler.assemble(...)` → same prompt framing as 7.1 + prior turns → `modelGateway.generate(prompt, ModelTier.BIG)`. Reuse the 7.1 system framing + `composePrompt`/`MAX_TURNS` (extract a shared prompt helper rather than duplicating).
  - [x] Investor profile: a static constant (Decision 1) — e.g. "Canadian solo investor; CAD home currency; TFSA/RRSP context" — defined once (constant or `@ConfigurationProperties` default). No new entity.
- [x] **Task 4 — REST endpoint** (AC: #3)
  - [x] `POST /api/portfolio/chat` — add a `PortfolioChatController` (in `com.argus.conversation`, `@RequestMapping("/api/portfolio")`) or a method on an existing `/api/portfolio` controller. `ChatRequest` body, returns `ChatMessage("assistant", …)`. Calls `ChatValidation.validate` then `conversation.askAboutPortfolio(...)`. Session-gated by the existing `SessionAuthFilter` (verify `/api/portfolio/**` is not allowlisted). camelCase.
- [x] **Task 5 — Frontend: shared ChatPanel + PortfolioChat + launcher** (AC: #5, #2)
  - [x] Extract `features/conversation/ChatPanel.tsx` from `RecommendationChat.tsx`: props `{ title, subtitle?, emptyStateText, placeholder, sendFn: (messages, signal) => Promise<ChatMessage>, onClose }`. Move all the message-state / motion / AbortController / thinking-indicator / error logic into it. Refactor `RecommendationChat` to a thin wrapper (preserves 7.1 behavior).
  - [x] `features/conversation/PortfolioChat.tsx`: thin wrapper over `ChatPanel` with portfolio copy + `sendFn = (msgs, signal) => sendPortfolioChat(msgs, signal)`.
  - [x] `lib/apiClient.ts`: `sendPortfolioChat(messages, signal?)` → `apiPost("/api/portfolio/chat", { messages }, signal)`.
  - [x] Add a global "Ask AI" launcher in `components/shell/TopBar.tsx` (after the HealthScoreBadge) that opens `PortfolioChat` (lift `open` state locally in TopBar; no global provider).
- [x] **Task 6 — Tests + verify** (AC: #6)
  - [x] Unit: `PortfolioContextAssemblerTest` (block contains a holding, health deduction, a calendar event, a recent rec line, the profile); `ConversationServiceTest` add `askAboutPortfolio` case (routes BIG, returns content, includes prior turns) with mocks.
  - [x] Integration: `PortfolioChatIntegrationTest` (`@ActiveProfiles("dev")`, Testcontainers): pin/login, seed a position + a recommendation + a calendar event, `POST /api/portfolio/chat` → 200 assistant + non-empty; 401 unauthenticated; 400 empty thread; 413 oversized.
  - [x] `./mvnw verify` green; `npm run lint` + `npm run build` clean.
- [x] **Task 7 — Mac Mini deferral** (AC: #4)
  - [x] Extend `docs/mac-mini-validation.md` §5 (or add §6) for portfolio chat: real `gemma4:26b` answer is grounded across holdings/health/calendar/recs and within ≤15s warm. Log any laptop-deferred pieces in `deferred-work.md`.

### Review Findings (code-review 2026-06-23)

Adversarial 3-layer review (Blind Hunter, Edge Case Hunter, Acceptance Auditor, Opus 4.8). All 6 ACs + all Decisions verified satisfied; no scope creep; 7.1 behavior preserved by the refactor. Triage: 5 patch, 2 deferred, 2 dismissed.

- [x] [Review][Patch] Cap holdings in the portfolio grounding block — `PortfolioContextAssembler` now sorts by weight, renders the top `MAX_HOLDINGS=25`, and appends "…and N smaller holdings"; recs (≤10) + events (≤14d) already capped. [edge, Med] [backend/.../conversation/PortfolioContextAssembler.java]
- [x] [Review][Patch] Abort-on-re-render fixed — `ChatPanel`'s AbortController cleanup moved to a mount-only `useEffect(…, [])` so an unstable `onClose` from a re-rendering parent (TopBar) can't abort an in-flight request. [blind, Med] [frontend/.../features/conversation/ChatPanel.tsx]
- [x] [Review][Patch] Recent-rec line now includes the bear % (bull/bear/confidence). [auditor, Low] [backend/.../conversation/PortfolioContextAssembler.java]
- [x] [Review][Patch] Dialog accessible name restored — `aria-label` composed from title + subtitle ("Ask AI — AAPL"). [blind, Low] [frontend/.../features/conversation/ChatPanel.tsx]
- [x] [Review][Patch] Same-day events render "today" instead of "in 0 days". [auditor+edge, Low] [backend/.../conversation/PortfolioContextAssembler.java]
- [x] [Review][Defer] Shared chat dialog lacks `aria-modal`/focus-trap/initial-focus/body-scroll-lock — inherited from 7.1's `RecommendationChat`, now exposed globally via the TopBar launcher [frontend ChatPanel.tsx] — deferred, pre-existing a11y polish for the shared panel
- [x] [Review][Defer] Quiet-period annotation omitted from calendar grounding (no `EarningsQuietPeriodService` call) [backend ConversationService/PortfolioContextAssembler] — deferred, already logged in deferred-work.md (story-7.2)
- Dismissed (2): dead defensive `days < 0` branch in the assembler (unreachable — repo window is `[today, today+14]`; harmless guard); `role="dialog"` nested in `<header>` (functionally fine — the panel is `position: fixed`; semantic nit only).

## Dev Notes

### ⭐ Decisions to confirm (recommended defaults chosen so dev can proceed)
1. **Investor profile — recommended: a static "Canadian investor" constant (no entity).** No profile/investor/settings entity exists in the backend (only `AppSettings` = session timeout). Ground it from a constant now; a persisted profile + the full Canadian lens (CAD figures, TFSA/RRSP/withholding) is **Story 7.5 (FR-34)** + a future profile entity. *(vs. building a profile entity now — out of 7.2 scope.)*
2. **Entry point — recommended: a global "Ask AI" launcher in the TopBar** (always visible across the dashboard) rather than only on the portfolio page. *(FR-31 says "from the main dashboard".)*
3. **UI reuse — recommended: extract a shared `ChatPanel`** and make both `RecommendationChat` (7.1) and `PortfolioChat` thin wrappers. Keeps one polished chat implementation; 7.1 behavior must be preserved.
4. **Calendar window — recommended: next ~14 days** (matches the `/api/calendar/upcoming` default) for grounding; cap recent recs at ~10.

### Scope boundaries (don't over-build)
- **Haiku escalation / "Get deeper analysis"** (FR-32) → **Story 7.3**. 7.2 is `ModelTier.BIG` (local) only; same `HaikuFallback`-stub caveat as 7.1 (the code-review HIGH deferred to 7.3 — see `deferred-work.md`).
- **4 personas / "Get Perspectives"** (FR-33) and the **Canadian Investor lens** (FR-34) → **Stories 7.4/7.5**. 7.2 uses a flat investor-profile constant, not persona voices.
- **Persisted investor profile entity** → future (with 7.5). **Token streaming + pre-warm**, **server-side persistence** → deferred (same as 7.1; Mini-relevant / out of scope).
- **No migration** — 7.2 persists nothing; next free Flyway version remains **V20**.

### Reuse — read these first (prevents reinvention / regressions)
- **7.1 conversation infra (the template for this story):** `conversation/ConversationService.java` (system framing, `composePrompt`, `MAX_TURNS`, `generate(BIG)`), `conversation/RecommendationContextAssembler.java` (the grounding-block style to mirror), `conversation/ConversationController.java` (the `validate(...)` to extract into `ChatValidation`), `conversation/ChatRequest.java`/`ChatMessage.java`. **Extract shared pieces; don't fork.**
- `portfolio/LivePortfolioService.currentSnapshot()` → `PortfolioSnapshot`/`PositionValue` (ticker, companyName, weightPercent, cadMarketValue, cadPnl, …); `portfolio/HealthScoreService.compute()` → `HealthScoreResult(score, List<HealthDeduction>)`, `HealthDeduction(code, label, points, reason, suggestion)`.
- `calendar/Agent7CalendarService` + `calendar/CalendarController` (`GET /api/calendar/upcoming?days=14`) + `calendar/CalendarEvent` (type, ticker, title, eventDate); `calendar/CalendarEventRepository.findByEventDateBetweenOrderByEventDateAsc`.
- `recommendation/RecommendationService.recent()` → top-50 newest, signals eager; `Recommendation` getters (ticker, direction, bull/bear/confidence, status).
- Frontend: `features/conversation/RecommendationChat.tsx` (extract `ChatPanel`), `lib/apiClient.ts` (`ChatMessage`, `sendRecommendationChat`, `apiPost(…, signal)`), `components/shell/TopBar.tsx` (launcher placement, after `HealthScoreBadge`), `features/portfolio/HealthScoreBreakdown.tsx` (dialog/token patterns).

### Architecture / convention guardrails (mandatory)
- **Single LLM home:** all model access via `com.argus.model` Model Gateway (BIG tier); Ask-AI context assembly in `com.argus.conversation`. Frontend `features/conversation`. [architecture.md#Decision 1; #F10 Ask AI → conversation+model]
- **REST + RFC 9457:** session-gated `/api/portfolio/chat`, resource returned directly, Problem Details on error, camelCase (Jackson 3). Frontend ↔ backend only via REST/STOMP. [architecture.md#Decision 6]
- **No LLM-invented numbers:** the grounding block carries the engine/portfolio numbers; the prompt instructs the model to cite, not fabricate. Health score + probabilities are rule-derived (GAP-6), never LLM. [PRD#FR-31]
- **Privacy seam (for 7.3):** 7.2 is local-only so full portfolio context is fine; never send raw positions to an external API — when 7.3 adds Haiku, only sanitized context may leave the network. [architecture.md#Privacy]
- **Tests:** JUnit Jupiter (no AssertJ); mock the gateway/services for unit tests, `dev`-profile `MockChatModel` for the integration slice (no Ollama/network); Testcontainers for the DB; auth via `/api/auth/pin` + `/api/auth/login`. Mirror `ConversationIntegrationTest` / `RecommendationControllerIntegrationTest`.

### Files to touch
- **New (backend):** `conversation/ChatValidation.java`, `conversation/PortfolioContextAssembler.java`, `conversation/PortfolioChatController.java`; tests `conversation/PortfolioContextAssemblerTest.java`, `conversation/PortfolioChatIntegrationTest.java`.
- **Modified (backend):** `conversation/ConversationController.java` (use `ChatValidation`), `conversation/ConversationService.java` (add `askAboutPortfolio` + share prompt helper), `conversation/ConversationServiceTest.java` (portfolio case).
- **New (frontend):** `src/features/conversation/ChatPanel.tsx`, `src/features/conversation/PortfolioChat.tsx`.
- **Modified (frontend):** `src/features/conversation/RecommendationChat.tsx` (wrap `ChatPanel`), `src/lib/apiClient.ts` (`sendPortfolioChat`), `src/components/shell/TopBar.tsx` (launcher).
- **Modified (docs):** `docs/mac-mini-validation.md`, `deferred-work.md`.

### Latest tech notes
- Same model stack as 7.1: `gemma4:26b` via Ollama under `prod` (`ARGUS_MODEL_KEEP_ALIVE=5m`, ~28s cold-load → "warming up" UI); `dev` profile excludes Ollama autoconfig and injects `MockChatModel`. Portfolio chat's larger context (holdings + calendar + recs) makes the prompt bigger than 7.1 — keep the assembled block bounded (caps above) to stay within context + latency.

### Project Structure Notes
Stays inside the reserved `com.argus.conversation` package + `features/conversation` folder (architecture's F10 slot). No structure variance, no migration. Reuses portfolio/calendar/recommendation/model packages without changing their domain.

### References
- [Source: epics.md#Story 7.2] — holdings + health breakdown + calendar + recent recs as context.
- [Source: prd.md#FR-31] — Portfolio Chat: holdings + health-score breakdown + upcoming calendar events + most recent recommendations + investor profile; example questions.
- [Source: architecture.md#Decision 1, #Decision 6, #F10 Ask AI → conversation+model, #Privacy].
- [Source: 7-1-recommendation-chat.md] — conversation infra, prompt framing, validation, dev MockChatModel test pattern, the deferred Haiku-stub HIGH.
- [Source: docs/mac-mini-validation.md §5] — Ask-AI live-model verification on the Mini.
- [Source: portfolio LivePortfolioService/HealthScoreService, calendar Agent7CalendarService/CalendarController, recommendation RecommendationService#recent].

## Open Questions (for the user)
1. **Investor profile:** default is a static "Canadian investor" constant (no entity); the full Canadian lens + persisted profile is 7.5/future. Confirm that's acceptable for 7.2.
2. **Entry point:** default is a global TopBar "Ask AI" launcher. Confirm vs. a portfolio-page-only button.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- `./mvnw verify` → **BUILD SUCCESS, 294 tests, 0 failures** (+7 vs 7.1's 287: PortfolioContextAssembler 2, ConversationService portfolio case +1, PortfolioChatIntegration 4). 7.1's `ConversationIntegrationTest` (6) stayed green after the `ChatValidation` extraction.
- `npm run lint` clean; `npm run build` (Next 16) clean, TypeScript clean.
- Decisions applied: static "Canadian investor" profile constant; global TopBar Ask-AI launcher.

### Completion Notes List

- **Reuse, not fork:** extracted `ChatValidation` (shared by `ConversationController` + new `PortfolioChatController`) and a frontend `ChatPanel` (both `RecommendationChat` and new `PortfolioChat` are now thin wrappers). 7.1 behavior/tests preserved.
- **Backend:** `PortfolioContextAssembler` (pure) renders holdings + health + upcoming calendar (≤14-day window) + recent recs (≤10) + investor profile; `ConversationService.askAboutPortfolio(messages)` gathers them (injects `CalendarEventRepository` + `RecommendationService`), reuses the shared `composePrompt`, routes to `ModelGateway.generate(BIG)`. `POST /api/portfolio/chat` (session-gated, RFC 9457, 400/413 validation). No LLM call outside the gateway; **no migration, V20 stays free.**
- **Frontend:** TopBar "Ask AI" launcher opens `PortfolioChat` (local state, no global provider); `sendPortfolioChat` added to `apiClient`. Shared `ChatPanel` keeps the AbortController/error-detail/warming-up behavior from the 7.1 review patches.
- **Investor profile:** static constant (no entity exists) — persisted profile + Canadian lens deferred to 7.5/future (`deferred-work.md`).
- **Laptop scope:** integration test runs `@ActiveProfiles("dev")` → real `MockChatModel`; real `gemma4:26b` grounding + ≤15s verification logged in `docs/mac-mini-validation.md` §6.

### File List

**New (backend):** `conversation/ChatValidation.java`, `conversation/PortfolioContextAssembler.java`, `conversation/PortfolioChatController.java`
**New (backend tests):** `conversation/PortfolioContextAssemblerTest.java`, `conversation/PortfolioChatIntegrationTest.java`
**Modified (backend):** `conversation/ConversationController.java` (use `ChatValidation`), `conversation/ConversationService.java` (add `askAboutPortfolio`, inject calendar/recs, generalize framing), `conversation/ConversationServiceTest.java` (new ctor + portfolio case)
**New (frontend):** `src/features/conversation/ChatPanel.tsx`, `src/features/conversation/PortfolioChat.tsx`
**Modified (frontend):** `src/features/conversation/RecommendationChat.tsx` (wrap `ChatPanel`), `src/lib/apiClient.ts` (`sendPortfolioChat`), `src/components/shell/TopBar.tsx` (Ask-AI launcher)
**Modified (docs):** `docs/mac-mini-validation.md` (§6), `_bmad-output/implementation-artifacts/deferred-work.md` (7.2 deferrals)

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-23 | Story created (create-story workflow). Second story of Epic 7. Dashboard portfolio chat (FR-31): new `PortfolioContextAssembler` (holdings + health + calendar + recent recs + static investor profile) → `ModelGateway.generate(BIG)`; session-gated `POST /api/portfolio/chat`; shared `ChatPanel` extracted from 7.1 with a TopBar Ask-AI launcher. Reuses 7.1 conversation infra (extract `ChatValidation` + prompt helper). Laptop scope: dev `MockChatModel`, full path tested without Ollama; real `gemma4:26b` ≤15s verification deferred to the Mac Mini. No migration (V20 stays free). Haiku escalation → 7.3; personas/Canadian lens → 7.4/7.5; persisted investor profile → future. Status → ready-for-dev. |
| 2026-06-23 | Implemented 7.2 (FR-31): portfolio chat through the Model Gateway (BIG) grounded in holdings + health + calendar + recent recs + static Canadian-investor profile; `POST /api/portfolio/chat`; extracted shared `ChatValidation` (backend) + `ChatPanel` (frontend) — `RecommendationChat`/`PortfolioChat` now thin wrappers, 7.1 preserved; TopBar Ask-AI launcher. 294 backend tests (+7) green; frontend lint+build clean. Mini-only live-model verification logged (§6). Status → review. |
| 2026-06-23 | Code review (adversarial 3-layer, Opus 4.8): all 6 ACs + Decisions verified, no scope creep, 7.1 preserved. 0 HIGH. 5 patches applied: cap holdings (top-25 by weight), fix abort-on-re-render (mount-only effect), add bear % to rec lines, restore dialog aria-label, "today" for same-day events. 2 deferred (shared-panel a11y focus-trap/scroll-lock; quiet-period annotation), 2 dismissed. 294 backend tests green; frontend lint+build clean. Status → done. |
