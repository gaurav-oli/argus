# Story 7.6: Persisted, user-editable investor profile

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the investor,
I want to record my risk tolerance, financial goal, and preferences and have them persist and be editable,
so that the portfolio chat and the Canadian persona reason about *me* — not just what my statements imply.

## Context & Rationale

Today the "investor profile" that grounds the portfolio chat (FR-31) and the Canadian persona (FR-34) is
**entirely derived** from imported accounts by `InvestorProfileService.describe()` — residency/home currency
come from `argus.investor.*` config, and the account-type mix / corporate flag / held currencies are read
live from `AccountMeta` + `Position`. There is **no place to capture the things that can't be inferred**:
risk tolerance, financial goal, target amount + timeline, and free-text preferences. `InvestorProfileService`'s
own Javadoc flags this as "the remaining part of Story 7.5" (see [Source: backend/src/main/java/com/argus/conversation/InvestorProfileService.java#L15-L21]).

This is a **follow-on enhancement** to Epic 7 — it deepens the grounding quality behind FR-31 and FR-34. It
is not a new PRD FR (there is no FR for risk tolerance/goals), but it has clear product roots in the
brainstorming session: Insight #1 proposed an *"Investor Profile page"* and Insight #2 proposed a *Goal
Tracker* — *"your financial goal is the destination; set target (amount + timeline)"*
[Source: _bmad-output/planning-artifacts/brainstorming/brainstorming-session-2026-06-11-2231.md#L748-L757].

**This story delivers only the user-EDITABLE profile + persistence + feeding it into grounding.** The
"learned behavior patterns" (auto-learned quality threshold, Insight #1) and the active Goal-Tracker
*rerouting alerts* (Insight #2) are explicitly OUT of scope — see Non-Goals.

## Acceptance Criteria

1. **Editable on the Profile page.** Given the `/profile` page, when I open it, then an "Investor Profile"
   section shows editable fields — **risk tolerance** (Conservative / Balanced / Growth / Aggressive),
   **financial goal** (free text), **target amount** (CAD) + **target date**, **residency**, **home
   currency**, and **preferences/notes** (free text) — pre-filled from the persisted profile, or showing
   sensible defaults on first run.
2. **Persistence.** Given I edit any field and Save, when I reload the page or restart the app, then the
   saved values are still shown — persisted in a single-row `investor_profile` table (get-or-create
   singleton, matching the `app_settings` / `notification_prefs` pattern).
3. **Portfolio-chat grounding uses it.** Given a saved profile, when `InvestorProfileService.describe()`
   builds the chat grounding, then the output includes the user-set risk tolerance, financial goal, and
   target (amount + date) **in addition to** the existing account-derived facts.
4. **Residency/home-currency override config, with fallback.** Given residency and/or home currency are set
   in the profile, then they are used everywhere the profile is consumed (portfolio chat grounding **and**
   the Canadian persona's residency assumption); given they are unset (null/blank), then the existing
   `argus.investor.*` config defaults are used — **no regression** vs today's behavior.
5. **Fresh-install safety.** Given no profile has ever been saved, when the chat is grounded or the page is
   opened, then everything works with defaults (get-or-create returns a default row; `describe()` produces
   the same output it does today).
6. **Session-gated API.** Given the write endpoints, then they sit under `/api/**` (session-gated by the
   existing `SessionAuthFilter`); `GET` returns the current profile, `PUT` validates and saves. A 401 from
   an expired session re-gates the UI via the existing handler — no new auth code.

## Tasks / Subtasks

- [ ] **Task 1 — Migration (AC: 2, 5)**
  - [ ] Add `backend/src/main/resources/db/migration/V44__investor_profile.sql` (V44 is the next free
        version — V43 is latest). Single-row table `investor_profile` with `id smallint PRIMARY KEY DEFAULT 1
        CHECK (id = 1)`, nullable columns `risk_tolerance text`, `financial_goal text`, `target_amount
        numeric(18,2)`, `target_date date`, `residency text`, `home_currency text`, `notes text`,
        `updated_at timestamptz NOT NULL DEFAULT now()`. Seed the row with `INSERT ... (id) VALUES (1) ON
        CONFLICT DO NOTHING`. Header-comment style per V38/V4 (what/why + story ref). Do NOT reuse a version
        number; forward-only.
- [ ] **Task 2 — Entity + repository (AC: 2, 5)**
  - [ ] `com.argus.conversation.InvestorProfile` — mutable `@Entity @Table(name = "investor_profile")`,
        `@Id private short id = SINGLETON_ID` (`static final short SINGLETON_ID = 1`), getters/setters,
        `updatedAt` stamped on write. Mirror `AppSettings` exactly (getters/setters, not a record).
  - [ ] `com.argus.conversation.InvestorProfileRepository extends JpaRepository<InvestorProfile, Short>`
        with `default Optional<InvestorProfile> findSingleton()` (mirror `AppSettingsRepository`).
  - [ ] Store `riskTolerance` as `String` in a small enum-like set validated at the controller (keep the DB
        column `text` to stay migration-light); OR a `RiskTolerance` enum persisted as its `name()`. Prefer
        an enum in Java for type-safety, persisted as text.
- [ ] **Task 3 — Wire into `InvestorProfileService` (AC: 3, 4, 5)** — READ THE FILE FIRST
  - [ ] Inject `InvestorProfileRepository`. In `describe()`, load the singleton (get-or-create defaulted).
  - [ ] **Residency/home currency:** use the profile's values when non-blank, else fall back to the injected
        `@Value("${argus.investor.residency:Canadian}")` / `home-currency:CAD` defaults. Keep the `@Value`
        fields as the fallback source — do not remove them.
  - [ ] Append the user-set fields to the built sentence: risk tolerance ("risk tolerance: Growth"),
        financial goal, and target ("targeting C$X by YYYY-MM-DD") — only when present. Preserve all existing
        account-derived output and ordering; append after it.
- [ ] **Task 4 — Canadian persona residency (AC: 4)**
  - [ ] `CanadianContextService` hard-assumes a Canadian investor. Source residency from the profile: if the
        profile residency is non-Canadian, the Canadian-lens block should be suppressed or softened (a
        non-Canadian investor doesn't get TFSA/RRSP/withholding framing). MVP: read residency via
        `InvestorProfileService` (or a small shared accessor) and skip the TFSA/RRSP/withholding note when
        residency is not "Canadian". Keep the FX/CAD-equivalent line only when home currency is CAD.
  - [ ] Do NOT break Story 7.5's `CanadianContextServiceTest` — extend it for the non-Canadian path.
- [ ] **Task 5 — REST API (AC: 1, 2, 6)**
  - [ ] `com.argus.conversation.InvestorProfileController` `@RestController @RequestMapping("/api/investor-profile")`
        (domain-namespaced like `NotificationPreferencesController` at `/api/notifications/preferences`, NOT
        under `/api/settings`). `GET` returns an `InvestorProfileView` record; `PUT` accepts an update record,
        validates (risk tolerance in the allowed set; target_amount ≥ 0; currency a 3-letter code), saves via
        a `@Transactional` service method (get-or-create → set fields → stamp `updatedAt` → save), returns the
        updated view (or 204 — match `SettingsController`'s choice; prefer returning the saved view for the
        frontend to reflect).
  - [ ] Use DTO **records** for request/response (per Watchlist/Settings convention); entity stays mutable.
- [ ] **Task 6 — Frontend (AC: 1, 2)**
  - [ ] `frontend/src/lib/apiClient.ts`: add `InvestorProfile` interface + `getInvestorProfile()` /
        `putInvestorProfile(profile)` mirroring `getNotificationPrefs`/`putNotificationPrefs` (apiGet/apiPut,
        `credentials:"include"`).
  - [ ] `frontend/src/features/profile/InvestorProfileSetting.tsx` — editable form: `useState` per field,
        `useEffect` load on mount (active-flag cleanup), async `save()` with `saving`/`savedAt`/`error` and
        the "✓ Saved" indicator (copy the shape of `NotificationPreferences.tsx`). Risk tolerance as a
        select/segmented control; goal/notes as text; target amount (number) + date; residency + home
        currency inputs.
  - [ ] Mount it as a new `SettingsCard` on `frontend/src/app/(dashboard)/profile/page.tsx` (alongside
        Appearance/Security/Notifications), following how `NotificationsSetting` is embedded.
- [ ] **Task 7 — Tests (AC: 3, 4, 5)**
  - [ ] `InvestorProfileServiceTest` (new): with a stubbed repository — (a) a fully-populated profile appears
        in `describe()`; (b) a blank profile falls back to config residency/currency and matches today's
        output; (c) profile residency overrides the config default.
  - [ ] Extend `CanadianContextServiceTest` for the non-Canadian residency path (Task 4).
  - [ ] Backend `mvn -o compile test-compile` + the two tests green; frontend `tsc --noEmit` + `eslint` green
        on the changed files.
- [ ] **Task 8 — Docs / bookkeeping**
  - [ ] Update `InvestorProfileService`'s Javadoc (drop the "remaining part of Story 7.5" note now that it's
        done). Add a Mac-Mini validation bullet to `docs/mac-mini-validation.md` §13 (or a new §): edit the
        profile in the running app, confirm it persists across restart and that the portfolio chat visibly
        reflects the risk tolerance/goal.

## Dev Notes

### Architecture patterns & constraints

- **Singleton settings is an established pattern — copy it, don't invent.** Two existing single-row entities:
  `AppSettings` (`@Id static final short SINGLETON_ID = 1`, DB `CHECK (id = 1)`, get-or-create via
  `findSingleton().orElseGet(AppSettings::new)`) and `NotificationPrefs`. Follow this exactly.
  [Source: backend/src/main/java/com/argus/security/AppSettings.java#L17-L20]
  [Source: backend/src/main/java/com/argus/security/AppSettingsRepository.java#L7-L12]
  [Source: backend/src/main/java/com/argus/security/SettingsService.java#L43-L50]
- **Entities are mutable POJOs (getters/setters), DTOs are records.** Do not model the entity as a record.
  Request/response bodies ARE records (see `WatchlistController.AddRequest` / `WatchlistView`,
  `SettingsController.SessionTimeout`).
  [Source: backend/src/main/java/com/argus/portfolio/AccountMeta.java#L44-L94]
  [Source: backend/src/main/java/com/argus/watchlist/WatchlistController.java#L63-L72]
- **Session gating is automatic** for anything under `/api/**` (path filter registered in `SecurityConfig`,
  order 0). No `@Secured`/annotations exist or are needed. The frontend already handles 401 by re-gating.
  [Source: backend/src/main/java/com/argus/security/SecurityConfig.java#L18-L24]
  [Source: frontend/src/lib/apiClient.ts#L63-L79]
- **Domain-namespaced endpoints.** `NotificationPrefs` lives at `/api/notifications/preferences`, not under
  `/api/settings`. Put the profile at `/api/investor-profile` in `com.argus.conversation`.
  [Source: backend/src/main/java/com/argus/notification/NotificationPreferencesController.java#L24-L32]
- **Migrations are forward-only, `V{N}__{snake}.sql`.** Next free version is **V44**. Use a header comment
  (what/why + story ref) and `ON CONFLICT DO NOTHING` for the seed row (safe re-run), per V38/V4.
  [Source: backend/src/main/resources/db/migration/V38__notification_prefs.sql]
  [Source: backend/src/main/resources/db/migration/V4__app_settings.sql#L1-L11]

### Source tree — files to touch

**Backend (new):** `conversation/InvestorProfile.java`, `conversation/InvestorProfileRepository.java`,
`conversation/InvestorProfileController.java`, `resources/db/migration/V44__investor_profile.sql`,
`test/.../conversation/InvestorProfileServiceTest.java`.
**Backend (UPDATE — read fully first):** `conversation/InvestorProfileService.java` (blend persisted fields
+ residency fallback), `persona/CanadianContextService.java` (residency-aware), `persona/PersonaService.java`
(only if residency threading requires it), `test/.../persona/CanadianContextServiceTest.java`.
**Frontend (new):** `features/profile/InvestorProfileSetting.tsx`.
**Frontend (UPDATE):** `lib/apiClient.ts` (interface + get/put), `app/(dashboard)/profile/page.tsx` (mount the card).

### Files being modified — current state & what must be preserved

- `InvestorProfileService.describe()` (backend/.../conversation/InvestorProfileService.java): builds a single
  sentence from `residency`/`homeCurrency` (`@Value`) + `AccountMeta.findAll()` + `Position` currencies, with
  a registered-account/USD tax clause. **Preserve** the full existing output; the persisted fields are
  APPENDED, and residency/home-currency become "profile value else config default". `@Transactional(readOnly
  = true)` on the read; the write lives in the new controller/service path (`@Transactional`, not read-only).
- `CanadianContextService.describe(...)` (Story 7.5): assumes a Canadian investor and emits TFSA/RRSP/RESP +
  15% withholding facts for US-listed names. **Must stay correct for the Canadian default**; only add a
  non-Canadian branch. Its 4 existing tests must still pass.

### Non-Goals (explicit — do not build)

- **Learned behavior patterns** (auto-derived quality threshold / "your learned patterns" from Insight #1) —
  future; this story is user-EDITABLE fields only.
- **Active Goal-Tracker rerouting** ("new conditions → adjusted route to your goal" alerts, Insight #2) — we
  *store* the target (amount + date) and surface it in grounding, but no on-track monitoring or alerts.
- **Multi-user / multiple profiles** — single-row singleton, consistent with the whole app being single-user.
- **Risk-tolerance-driven position sizing changes** — the profile informs the LLM grounding only; it does not
  alter `PositionSizer` or any scoring logic in this story.

### Testing standards

- Backend: JUnit 5 + Mockito (`mock(...)`, `when(...)`), plain constructor injection in tests (see
  `AgentSignalGathererTest`, `CanadianContextServiceTest`). Prefer testing the deterministic service logic
  with a stubbed repository over Spring-context tests.
- Frontend: no test harness for components in this area — verify via `tsc --noEmit` + `eslint`; behavior is
  Mac-Mini-validated (can't run the app on the MacBook).

### Project Structure Notes

- The new entity/repo/controller belong in `com.argus.conversation` (next to the consumer
  `InvestorProfileService`), not in `com.argus.security` — matches the domain-namespaced convention
  (`NotificationPrefs` lives in `com.argus.notification`, not `security`).
- No conflict with existing structure. The `/profile` route already exists and hosts settings cards, so the
  UI has a natural home with zero new routing.

### References

- [Source: backend/src/main/java/com/argus/conversation/InvestorProfileService.java#L15-L96] — current derived profile + the "remaining part of 7.5" note
- [Source: backend/src/main/java/com/argus/persona/CanadianContextService.java#L41-L62] — Canadian lens to make residency-aware
- [Source: backend/src/main/java/com/argus/persona/PersonaService.java#L124] — where canadaFacts is assembled
- [Source: backend/src/main/java/com/argus/security/AppSettings.java] + [AppSettingsRepository.java] + [SettingsService.java] + [SettingsController.java] — singleton settings pattern to copy
- [Source: backend/src/main/java/com/argus/notification/NotificationPreferencesController.java] — domain-namespaced GET/PUT settings endpoint
- [Source: frontend/src/features/notifications/NotificationPreferences.tsx] — editable settings form shape (state/load/save UX)
- [Source: frontend/src/app/(dashboard)/profile/page.tsx] — where to mount the new SettingsCard
- [Source: frontend/src/lib/apiClient.ts#L864-L868] — get/put settings helper pattern
- [Source: _bmad-output/planning-artifacts/epics.md#L70-L75] — FR-31 (portfolio chat grounding), FR-34 (Canadian persona) this deepens
- [Source: _bmad-output/planning-artifacts/brainstorming/brainstorming-session-2026-06-11-2231.md#L748-L757] — Investor Profile page + Goal Tracker product roots

## Dev Agent Record

### Agent Model Used

_TBD by dev-story_

### Debug Log References

### Completion Notes List

### File List
