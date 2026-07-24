---
baseline_commit: d7eb1b9
---
# Story 11.1: Trade Journal (F22)

Status: backlog

<!-- Scoping draft — not yet reviewed/approved for dev. Produced 2026-07-24 per user request
     ("start scoping the Trade Journal"), ahead of the Phase-2 PRD expansion pass the PRD itself
     calls for ("F16-F29 ... will receive full FR expansion in a PRD update pass before Phase 2
     architecture begins"). This story stands alone rather than waiting for that pass, scoped
     narrowly to F22 only. -->

## Story

As the investor,
I want a chronological journal of every recommendation I've Taken or Declined, with the frozen
reasoning and signals from that moment plus how it actually turned out,
so that I can review my own decision quality over time and see where I agreed or disagreed with
Agent 5 — not just the aggregate stats the Agents page already shows.

## Context & Rationale

**PRD anchor.** F22 ("Trade Journal & Performance Tracker", Phase 2 — Intelligence Layer):
"Log every trade; Agent 5 vs. own-decision comparison; feedback loop for Agent 5; built on the FR-15
decision-rationale snapshots."
[Source: _bmad-output/planning-artifacts/prds/prd-ProjectX-2026-06-15/prd.md#L607]

**What FR-15 already built (Story 6.7 — done).** `TradeConfirmationService.confirm()` freezes a
`TradeDecision` row per Taken/Declined call: ticker/direction/probabilities/confidence/signals +
the user's free-text reasoning, as a JSON `snapshot` string, immutable once written.
[Source: backend/src/main/java/com/argus/recommendation/TradeConfirmationService.java#L36-L95]
[Source: backend/src/main/java/com/argus/recommendation/TradeDecision.java]

**The gap this story fills.** Nothing today lets the user actually *see* their own decision
history. The only read paths over `TradeDecision` are aggregate: `PerformanceService.regret()`
(taken-vs-declined average return, bucketed) rendered as one stat card on the Agents page.
[Source: backend/src/main/java/com/argus/recommendation/PerformanceService.java#L155-L204]
[Source: frontend/src/features/agents/AgentPerformance.tsx#L234-L268]
There is no endpoint that lists individual `TradeDecision` rows, and no UI that renders one. Once a
recommendation is decided, `RecommendationCards.tsx` just removes the card from view client-side —
the decision is gone from sight forever except in the DB.
[Source: frontend/src/features/recommendations/RecommendationCards.tsx#L66]

**Two real gaps in FR-15 itself, in scope for this story (per user decision, 2026-07-24):**
1. `TradeDecision`/the snapshot never captures entry price or position size, despite the PRD's FR-15
   text implying it should ("opens a form to log entry price, position size"). It was never actually
   built — `TradeConfirmationService.confirm()`'s only inputs are `decision` + free-text `reasoning`.
   [Source: backend/src/main/java/com/argus/recommendation/RecommendationController.java#L59-L62,69]
2. The snapshot's `personaVerdicts` field is a hardcoded empty list — an explicit "Epic 7 seam" left
   when Epic 7 (personas) hadn't been built yet. It has been since (Story 7.4); the live persona
   verdicts for a recommendation are cheaply available via `PersonaService.verdictsFor(id)`, which
   reads a cache (`persona_verdicts` table) rather than making a live model call.
   [Source: backend/src/main/java/com/argus/recommendation/TradeConfirmationService.java#L93]
   [Source: backend/src/main/java/com/argus/persona/PersonaService.java#L81]
   [Source: backend/src/main/java/com/argus/persona/PersonaController.java#L24-L28]

**Explicitly narrower than "log every trade" (per user decision, 2026-07-24).** F22's literal text
suggests a general-purpose trade ledger. That's not what this story builds. There is no `Trade`
entity in `com.argus.portfolio` — only `Position` (current aggregate holding) and `PositionLot`
(purchase lots for ACB/cost-basis math, buy-side only, no sell/exit, no link to `Recommendation`).
[Source: backend/src/main/java/com/argus/portfolio/Position.java]
[Source: backend/src/main/java/com/argus/portfolio/PositionLot.java]
Building a real trade ledger (arbitrary manual entries, tied to actual positions, with real realized
P&L) is a materially bigger, separate effort with its own entity/migration/UI and no clear tie to
"Agent 5 vs. own-decision comparison" for trades that never touched a recommendation. **This story
scopes the journal to recommendation-driven decisions only** — every Taken/Declined call, which is
also the only slice the PRD explicitly ties back to FR-15's snapshot.

**Consequence of that narrowing, stated plainly:** "Agent 5 vs. own-decision comparison" here means
comparing the recommendation's stated probability/persona takes against the **paper-simulated**
outcome for that ticker/direction (`SimulatedTrade`, the existing $100-notional auto-paper-trade),
not against the user's real realized P&L — because real trades still aren't linked to `Position`.
This is the same data source `PerformanceService.regret()` already uses, so the journal's per-entry
outcome is consistent with the aggregate stat card the user already sees today.
[Source: backend/src/main/java/com/argus/recommendation/PerformanceService.java#L172-L179]

## Acceptance Criteria

1. **Entry-price/size capture at decision time.** Given I mark a recommendation "Taken", when I
   confirm, then I can optionally record an entry price and position size (shares); given I mark it
   "Declined", no entry price/size fields are shown. Both are optional — omitting them must not block
   confirming the decision (matches today's behavior where only reasoning is required-but-blank-ok).
2. **Snapshot captures persona verdicts.** Given a recommendation has cached persona verdicts at
   decision time, when the snapshot is frozen, then it includes each persona's stance + rationale
   (not an empty list); given no verdicts are cached yet, the snapshot's `personaVerdicts` is simply
   empty (no live model call is triggered from the confirm path — cache-only read).
3. **Journal list view.** Given I have one or more Taken/Declined decisions, when I open the Trade
   Journal, then I see them in reverse-chronological order (most recent first), each row showing:
   ticker, direction, decision (Taken/Declined), decided-at date, and outcome (Win/Loss/Pending —
   pending until a closed paper leg exists for that recommendation).
4. **Journal detail view.** Given a journal entry, when I expand/open it, then I see the full frozen
   snapshot: probabilities + confidence at decision time, the per-agent signal breakdown, the persona
   verdicts (if any were captured), my reasoning text, and — if I recorded them — entry price/size.
5. **Agent-5-vs-decision comparison per entry.** Given a decided recommendation with a closed paper
   leg, when I view its journal entry, then I see the paper-simulated return (vs-SPY excess when
   benchmarked, matching `PerformanceService`'s existing calculation) alongside what Agent 5's
   probability/direction called — i.e., did the outcome agree with the call I acted on (or declined)?
6. **No regression to existing behavior.** `POST /api/recommendations/{id}/decision`,
   `GET /api/recommendations/{id}/personas`, and `PerformanceService.regret()`'s existing aggregate
   output are unchanged for any decision that predates this story (no new required fields on
   existing rows; the two new snapshot fields degrade gracefully — see Non-Goals for old-row backfill
   posture).
7. **Session-gated API.** All new endpoints sit under `/api/**` (existing `SessionAuthFilter`
   coverage) — no new auth code.

## Tasks / Subtasks

- [ ] **Task 1 — Extend `TradeDecision` for entry price/size (AC: 1)**
  - [ ] Add nullable `entryPrice numeric(18,4)` and `positionSize numeric(18,4)` columns via
        `backend/src/main/resources/db/migration/V47__trade_decision_entry_details.sql` (V47 is next
        free — V46 is latest). Forward-only, header comment per convention.
  - [ ] Add matching fields + getters to `TradeDecision.java`; extend its constructor or add a
        setter-style "confirm details" method — decide based on whether `TradeConfirmationService`
        should accept them at `confirm()` time (AC 1 implies yes, in the same call).
  - [ ] Extend `RecommendationController.DecisionBody` with optional `entryPrice`/`positionSize`
        (nullable `BigDecimal`), threaded through to `TradeConfirmationService.confirm(...)`.
- [ ] **Task 2 — Persona verdicts in the snapshot (AC: 2)**
  - [ ] Inject `PersonaService` into `TradeConfirmationService`; in `snapshot()`, replace the
        hardcoded `List.of()` with `personaService.verdictsFor(rec.getId())` mapped to the same shape
        `PersonaController.PersonaView` uses (persona, key, lens, stance, rationale) — cache-only read,
        must not trigger `PersonaService`'s model-generation path.
  - [ ] Confirm `verdictsFor` is safe to call synchronously inside `TradeConfirmationService.confirm()`
        (read the method fully — Dev Notes below flag this as the one risk to verify before coding).
- [ ] **Task 3 — List/detail read endpoints (AC: 3, 4, 6, 7)**
  - [ ] `TradeDecisionRepository`: add `findAllByOrderByDecidedAtDesc(Pageable)` (or a `Page<>`-free
        `List<>` variant if pagination is deferred — decide against expected volume; MVP is
        single-user with a low decision cadence, a simple capped list may be enough for V1).
  - [ ] New `JournalController` (or extend `RecommendationController` — pick based on whether journal
        entries are conceptually recommendation-scoped or their own resource; lean toward a new
        `com.argus.recommendation.JournalController` at `/api/journal` since it's a distinct read
        surface over `TradeDecision`, not a recommendation sub-resource like `/personas` is).
  - [ ] `GET /api/journal` → list view DTO (id, ticker, direction, decision, decidedAt, outcome —
        derive outcome the same way `regret()` does: closed paper-leg average return → win/loss/null).
  - [ ] `GET /api/journal/{decisionId}` → detail view DTO: full parsed snapshot + entry price/size +
        the same per-entry outcome/comparison data as AC 5. Reuse `PerformanceService`'s
        closed-paper-leg lookup logic rather than duplicating it — consider extracting a small shared
        helper (`outcomeFor(recommendationId)`) if `PerformanceService` and the new controller/service
        both need it.
- [ ] **Task 4 — Frontend: Trade Journal page (AC: 3, 4, 5)**
  - [ ] `frontend/src/lib/apiClient.ts`: `JournalEntry`/`JournalDetail` interfaces + `getJournal()` /
        `getJournalEntry(id)`, mirroring the `RecommendationCard`/`getRecommendations` pattern.
  - [ ] New `frontend/src/features/journal/TradeJournal.tsx` — list (AC 3) + expand-to-detail (AC 4),
        each entry showing the comparison (AC 5). Follow the existing card/list visual language
        (`AgentPerformance.tsx`'s `RegretCard` for the comparison framing, `RecommendationCards.tsx`
        for the signal-breakdown rendering — don't invent a new visual pattern).
  - [ ] **Placement decision (flagged for review, not yet made):** the nav is deliberately capped at
        5 top-level destinations (`navItems.ts` comment: "Single source of truth for the 5 primary
        destinations"). Recommend mounting the journal as a new section on the existing **Agents**
        page (next to the aggregate Regret card it complements) rather than adding a 6th nav item or
        a new top-level route. Confirm this placement before Task 4 UI work starts.
- [ ] **Task 5 — Extend the "Taken" confirmation form (AC: 1)**
  - [ ] `RecommendationCards.tsx`'s `decide()` currently uses `window.prompt()` for reasoning only.
        Taking a trade needs a small real form (entry price + shares, both optional) instead of a
        second `prompt()` call — a minimal inline expand, not a new modal system, consistent with the
        existing card's compactness.
- [ ] **Task 6 — Tests (AC: all)**
  - [ ] `TradeConfirmationServiceTest`: entry price/size persist when provided and stay null when
        omitted; snapshot includes real persona verdicts when cached, empty list when not (mock
        `PersonaService`).
  - [ ] New `JournalControllerTest`/`JournalServiceTest` (naming TBD by Task 3's controller-vs-service
        split): list ordering, outcome derivation matches `regret()`'s existing win/loss logic,
        detail view returns the full snapshot including entry price/size.
  - [ ] Frontend: `tsc --noEmit` + `eslint` on all changed files (no test harness in this frontend
        project, per established convention — see [[epic-1-hardening-status]] and prior story notes).
- [ ] **Task 7 — Docs / bookkeeping**
  - [ ] Update `TradeConfirmationService`'s class Javadoc to drop the "Persona verdicts ... are an
        empty seam for now" note.
  - [ ] Add a Mac-Mini validation bullet to `docs/mac-mini-validation.md` once implemented (this repo
        has no local-model-dependent path here — the journal itself needs no Mini-only validation,
        but confirm end-to-end against the live paper-trade close data as the other Epic 9 stat cards
        did).

## Dev Notes

### Architecture patterns & constraints

- **`TradeDecision` snapshot is immutable once written — new fields must not require backfilling old
  rows.** Existing rows have no `entryPrice`/`positionSize` and a `personaVerdicts: []` snapshot;
  the journal UI must render "not recorded" gracefully for pre-this-story rows, not error or show a
  misleading zero. [Source: backend/src/main/java/com/argus/recommendation/TradeDecision.java#L14-L16]
- **DTOs are records, entities are mutable POJOs** — same convention as every other story in this
  codebase (see 7.6's Dev Notes for the canonical statement of this rule). Follow it here too.
- **Migrations are forward-only, `V{N}__{snake}.sql`, next free is V47.** Header comment (what/why +
  story ref), matching V44/V45/V46's style.
- **Session gating is automatic for `/api/**`** (`SecurityConfig` path filter, order 0) — no new auth
  annotations needed for the new `JournalController`.
  [Source: backend/src/main/java/com/argus/security/SecurityConfig.java#L18-L24]
- **Reuse `PerformanceService`'s outcome-derivation logic rather than re-deriving it.** `regret()`
  already computes "closed paper leg average return, direction-adjusted, vs-SPY-excess when
  benchmarked" per recommendation — the journal's per-entry outcome (AC 3, AC 5) must use the exact
  same derivation so the journal and the Agents-page aggregate never disagree about whether a given
  decision was a win or a loss. Extract a shared method rather than copy the logic; do not
  reimplement independently.
  [Source: backend/src/main/java/com/argus/recommendation/PerformanceService.java#L166-L196]
- **`PersonaService.verdictsFor` reads a cache; confirm it doesn't fall back to a live model call
  under any condition before wiring it into the synchronous `confirm()` path.** If it can block on
  model generation, Task 2 needs a different integration (e.g., snapshot persona verdicts lazily /
  best-effort, or explicitly skip if not already cached rather than trigger generation from a
  decision-confirmation click). Read `PersonaService.verdictsFor` fully before implementing — this
  is the one part of the plan not yet fully verified against the method body itself.
  [Source: backend/src/main/java/com/argus/persona/PersonaService.java#L81]

### Source tree — files to touch

**Backend (new):** `resources/db/migration/V47__trade_decision_entry_details.sql`,
`recommendation/JournalController.java` (name TBD), possibly `recommendation/JournalService.java` if
outcome-derivation extraction warrants its own service, `test/.../recommendation/JournalControllerTest.java`
or `JournalServiceTest.java`.
**Backend (UPDATE — read fully first):** `recommendation/TradeDecision.java` (new columns),
`recommendation/TradeConfirmationService.java` (persona verdicts + entry details in `confirm()`/
`snapshot()`), `recommendation/RecommendationController.java` (`DecisionBody` gains optional fields),
`recommendation/PerformanceService.java` (only if outcome logic is extracted to a shared helper),
`test/.../recommendation/TradeConfirmationServiceTest.java` (does this test file exist yet? — verify
before assuming; Story 6.7's completion notes didn't confirm one explicitly).
**Frontend (new):** `features/journal/TradeJournal.tsx`.
**Frontend (UPDATE):** `lib/apiClient.ts` (journal interfaces + fetchers, extend `DecisionBody`-shaped
payload for `decideRecommendation`), `features/recommendations/RecommendationCards.tsx` (entry
price/size mini-form on Take), the Agents page (mount point — file TBD by the placement decision in
Task 4).

### Non-Goals (explicit — do not build)

- **General-purpose trade logging independent of a Recommendation.** No new `Trade` entity, no manual
  "log any trade" form unrelated to Agent 5's recommendations. See Context & Rationale for why this
  was narrowed.
- **Real P&L against actual `Position`/`PositionLot` holdings.** The comparison is against the
  paper-simulated outcome only (same data `regret()` already uses) — wiring real-portfolio realized
  P&L into the journal is future work contingent on linking `TradeDecision`/`Recommendation` to real
  positions, which doesn't exist today.
- **Backfilling entry price/size or persona verdicts onto existing `TradeDecision` rows.** New fields
  apply going forward only; old rows render as "not recorded".
- **Auto Post-Mortems (F28b).** A separate, later PRD item that also builds on the FR-15 snapshot —
  do not bundle its "why this was wrong" LLM retrospective into this story even though it's adjacent.
- **Editing or deleting a past journal entry.** The snapshot is immutable by design (FR-15); the
  journal is read-only over it. (Recording an outcome later, via the existing `recordOutcome`/
  `recordOutcomeFromPaperTrade` paths, is unaffected and already exists.)

### Testing standards

- Backend: JUnit 5 + Mockito, plain constructor injection — same pattern as
  `InvestorProfileServiceTest`/`CanadianContextServiceTest`. Prefer testing service logic with
  stubbed repositories over full Spring-context tests where possible; this codebase's Testcontainers
  integration tests require Docker (unavailable on the dev MacBook per prior stories' notes — verify
  current dev-machine capability before assuming full integration coverage is runnable locally).
- Frontend: no test harness in this project — verify via `tsc --noEmit` + `eslint`.

### Project Structure Notes

- New backend code belongs in `com.argus.recommendation` (next to `TradeDecision`/
  `TradeConfirmationService`/`PerformanceService`), not a new top-level package — this is a read
  surface over existing recommendation/decision data, not a new domain.
- Frontend: new `features/journal/` directory, consistent with `features/agents/`,
  `features/recommendations/` being feature-scoped rather than route-scoped.

### References

- [Source: _bmad-output/planning-artifacts/prds/prd-ProjectX-2026-06-15/prd.md#L265-L273] — FR-15 (this builds on it)
- [Source: _bmad-output/planning-artifacts/prds/prd-ProjectX-2026-06-15/prd.md#L598-L618] — F16-F29 Phase 2 list, F22 entry
- [Source: backend/src/main/java/com/argus/recommendation/TradeDecision.java] — entity this story extends
- [Source: backend/src/main/java/com/argus/recommendation/TradeConfirmationService.java] — snapshot-building logic to extend
- [Source: backend/src/main/java/com/argus/recommendation/RecommendationController.java] — existing decision endpoint + DTOs
- [Source: backend/src/main/java/com/argus/recommendation/PerformanceService.java#L155-L210] — regret()/outcome-derivation logic to reuse, not duplicate
- [Source: backend/src/main/java/com/argus/persona/PersonaService.java] + [PersonaController.java] — persona verdict source for the snapshot fix
- [Source: backend/src/main/java/com/argus/portfolio/Position.java] + [PositionLot.java] — confirms no real trade-ledger entity exists (why scope is narrowed)
- [Source: frontend/src/features/recommendations/RecommendationCards.tsx] — existing decide() flow to extend
- [Source: frontend/src/features/agents/AgentPerformance.tsx#L234-L268] — existing aggregate regret UI, visual pattern to follow + likely mount point
- [Source: frontend/src/components/shell/navItems.ts] — 5-destination nav constraint driving the placement recommendation
- [Source: _bmad-output/implementation-artifacts/7-6-persisted-investor-profile.md] — template this story file follows

## Change Log

| Date | Change |
|------|--------|
| 2026-07-24 | Scoping draft written per user request. Two scope decisions resolved with the user before drafting: (1) V1 covers recommendation-driven decisions only, not a general trade ledger; (2) the two FR-15 gaps (entry price/size, persona verdicts) are fixed as part of this story rather than deferred. Not yet reviewed/approved — Status remains `backlog` until validated (e.g. via a validate-create-story pass) and picked up. |
