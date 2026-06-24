# Deferred Work

## ⏳ TODO — Story 1.3 (RAM + latency validation spike) — MUST run on the Mac Mini

**Status: deferred, not started (still `backlog` in sprint-status).** Story 1.3 measures Gemma 4 26B MoE memory + latency under worst-case load (DBs + JVM + a generation request) and is the GAP-1/2 gate. It **cannot run on this dev machine** (M5 MacBook, 16GB — can't host the ~15GB 26B model). It requires the **Mac Mini (M3, 28GB)** with Ollama + the model, reached over Tailscale.

**Plan (per user, 2026-06-18):** clone the repo on the Mini later and execute 1.3 there. Stories 1.4+ are being built on the laptop in the meantime (1.4 reordered ahead of 1.3 — low risk, since the Model Gateway is model-swappable). Revisit 1.3 once the Mini is set up; its outcome may adjust the `prod` model binding/keep-alive policy that 1.4 introduces.

## Deferred from: story-1.6 (2026-06-18)

- **springdoc-openapi (Swagger UI)** — Architecture Decision 6 lists it for personal API reference, but it was deferred from Story 1.6 (not in that AC, and springdoc needs a confirmed Boot-4-compatible version — same major-version caution as Boot 4 / Spring AI 2.0 / Testcontainers 2.x). Add in a small later story once a compatible springdoc release is verified.
- **Browser end-to-end of the REST+STOMP round-trip** — Story 1.6 proved the wire via a Java STOMP client + live curl, and shipped `frontend/src/lib/apiClient.ts` + `wsClient.ts`. The visual browser round-trip is wired when the dashboard shell exists (Story 1.7).

## Deferred from: code review of story-1.1 (2026-06-18)

- **`mvnw.cmd` line-ending mismatch** — `backend/.gitattributes` declares `*.cmd text eol=crlf`, but the committed `mvnw.cmd` blob is stored LF. On a fresh clone (especially Windows) git will renormalize and show the file as modified / emit "LF will be replaced by CRLF". Deferred: this is stock Spring Initializr output, the project is Mac-only and solo, and the impact is Windows-only. Revisit only if a Windows dev joins.
- ~~**No profile guard on the temporary DB-disabling config**~~ — **RESOLVED by Story 1.2 (2026-06-18):** the `DataSourceAutoConfiguration` exclude and the disabled Redis health indicator were removed when the real Postgres + Redis datasource was wired in. No longer applicable.

## Deferred from: code review of story-1.2 (2026-06-18)

- **PG18 data lives in a major-version subdir inside the volume** — the `pgvector/pgvector:0.8.2-pg18` image stores data at `/var/lib/postgresql/18/docker` within the `argus-pgdata` volume. The FR-40 backup story (Epic 10) must account for this: a future major-version bump (pg19) would create a `19/` subdir in the same volume, so volume-snapshot/`pg_dump` backup tooling must not hardcode the `18/` path. Deferred: relevant only when the backup story is implemented.

## Deferred from: code review of story-3.1 (2026-06-22)

- **Duplicate-position accumulation on re-import / multi-lot** — `confirmImport` does `saveAll` with no dedup/merge and `positions` has only a non-unique `idx_positions_ticker`. Re-uploading + confirming the same statement (or a ticker appearing on two lot lines) creates duplicate rows. **Deferred to Story 3.2**, which introduces ACB lots + weighted-average ACB across lots of the same security (the correct place to decide merge-vs-keep-lots) and to 3.7 (manual edit/remove). Storing lots as separate rows is an acceptable interim until 3.2 owns the merge model.
- **Silent USD currency default** — when a row has no explicit USD/CAD token, the parser defaults `costBasisCurrency` to `USD` without flagging. **Deferred to Story 3.2** (ACB with purchase-time FX), which owns currency/FX handling and is where a guessed currency must be surfaced/confirmed.
- **Heuristic parser limitations (junk-line-with-numbers, positional shares-then-cost assumption, multi-line/wrapped rows, non-ISO date *parsing*)** — deterministic line heuristics can mis-assign or miss data on layouts that differ from the assumed column order. The ≥95% parse-accuracy target (A-14) and broader layout/date-format support are future parser-refinement work; the no-silent-drop guarantee still holds (unread fields are flagged). LLM-assisted parsing (Mini-only) remains the longer-term option noted in the story.
- **Unbounded extracted-text / page-count on a malicious PDF** — the 15 MB byte cap does not bound extracted text or page count, so a small highly-compressed/many-page PDF could expand and run the regex scan over huge text (self-DoS). Low risk under the single-user, Tailscale-only threat model; add a page/char ceiling as future hardening.
- **BigDecimal → JS number precision** — `shares`/`costBasis` are exact `NUMERIC`/`BigDecimal` server-side but typed `number` in TS, so values beyond IEEE-754 precision would round in the preview/holdings UI. Exact at realistic personal-portfolio magnitudes; switch to string serialization if extreme values ever appear.
- **`raw_holdings` jsonb read has no failure path** — `confirmImport` deserializes the staged JSON straight into `List<ParsedHolding>`; if the record shape changes across future migrations a stored batch could fail to read (→ 500). Add a tolerant read/version guard when the shape next changes.

## Deferred from: code review of story-3.2 (2026-06-22)

- **Per-lot FX on confirm (multi-lot positions)** — `confirmFx` applies one rate/date to ALL estimated lots of a position. Correct today (import → exactly one lot per position), but once **Story 3.7** adds manual multi-lot entry, lots with different trade dates must each resolve their own purchase-time FX. Make confirm-FX per-lot when 3.7 lands.
- **Mixed-currency-per-position guard** — `AcbCalculator` sums trade-currency `costBasis` across lots and labels with the first lot's currency. Not reachable now (one currency per ticker, one lot per position); add a guard/assertion when multi-lot (3.7) makes heterogeneous lots possible. The CAD leg is always correct (per-lot FX).
- **UI confirm-by-date** — the FX-estimated affordance accepts a rate only; the backend also supports confirming by date (→ looked up). Add a date option to the UI in a later polish pass.
- **Residual silent USD currency default** — `StatementParser` still defaults a row with no USD/CAD token to USD without flagging; 3.2 only surfaces FX uncertainty when the *date* is also missing. Full currency-confidence tracking (flag "currency assumed") is a future parser refinement.
- **V6 backfill integration test** — the backfill (3.1 positions → one lot each) has no test, because Flyway runs migrations once at context start and the integration test `reset()` wipes rows. Add a dedicated migration-test harness later. (Backfill SQL is simple and the null-shares risk is not reachable — parser guarantees non-null shares.)
- **Future-dated FX confirm** — `confirmFx` by date doesn't reject a future purchase date (BoC may return today's rate). Add a `date <= today` guard in a later pass; implausible user action.

## Deferred from: code review of story-3.3 (2026-06-22)

- **Multi-match disambiguation on confirm** — when >1 holding shares a ticker, the action is `pending` but `confirm` can't pick which position (no `positionId` in the confirm body). With the new duplicate-ticker guard this is now rare; full disambiguation (and merging two holdings of the same security) belongs to **Story 3.7** (manual position edit / lot merge).
- **Duplicate-record idempotency** — recording the same auto-applicable action twice applies it twice (e.g. a split scaling by ratio²). The `ex_date` + `source` columns exist for dedup but aren't used yet; add an idempotency guard when the **Finnhub detector** is built (it would re-detect the same split on later polls).
- **Position-deletion handling** — `corporate_actions.position_id` is `ON DELETE SET NULL`; once a position-delete path exists (3.7), revisit `resolvePosition`'s by-ticker fallback so a confirm can't apply to a coincidental same-ticker position, and reconcile `applied` actions whose position was removed.
- **Dedicated ticker-alias artifact + cross-symbol history linking** — the old→new symbol mapping currently lives only on the action row; a queryable alias + linking news/prices/recommendations across symbols lands with **Epics 4/6** when that data exists.
- **`window.location.reload()` → targeted refetch** — `CorporateActions.tsx` reloads the page after an action applies; replace with a positions+actions refetch (lift shared state) in a later UI polish pass.

## Deferred from: code review of Epic-3 remainder (3.4–3.9) (2026-06-23)

Adversarial 3-layer batch review of stories 3.4–3.9. **0 High AC violations**; all cross-cutting invariants held (BigDecimal money, rule-engine/no-LLM health score, single `/topic/portfolio` feed, key-gated Finnhub, BoC FX, lots-as-truth via `PositionAcbService`, session-gating + RFC 9457, V8–V10 forward-only, scope deferrals honored). 11 fixes applied in-batch (weights-sum-100 incl. FX-estimated, edit-preserves-confirmed-FX, skip 0/empty daily captures, reject future acquisition dates, Finnhub WS reconnect-on-drop, 1D/sparse chart empty state, Day-%% colour, company-name field, card order, +weight test). Deferred:

- **Previous-close daily re-seed (live day P&L).** `previousCloses` is seeded once at feed start and never refreshed across trading days, so after an overnight rollover day P&L is measured against a stale close. Fix belongs with the live-feed's daily operation (re-fetch quotes each session) — validate on the Mini with the Finnhub key (laptop has no live feed).
- **Evict / re-key in-memory price + previous-close maps on ticker rename/remove, and re-subscribe the live feed** to the new symbol set. Harmless at single-user scale now (snapshot only iterates current positions); wire with the live-feed work on the Mini.
- **Health-score weighting in `double` + FX-estimated names excluded from the weight base.** The score is a coarse heuristic so float is acceptable, and FX-estimated holdings already self-flag via the data-quality deduction; revisit to BigDecimal weighting + include estimated names once their FX is confirmed.
- **`HealthScoreBadge` doesn't live-refresh after a manual position change** (no health event on `/topic/portfolio`); stale until reload. Add a refetch on snapshot push or a lightweight poll later.
- **Dashboard home `HealthScoreRing` still renders mock data with old 75/50 thresholds** — out of the 3.8/3.9 scope (which targeted the top-bar badge), but a second inconsistent score in the running app; wire it to the real score in a small follow-up.
- **Breakdown popover focus-trap/restore** (a11y polish) and **all-null column sort affordance** in the holdings table — minor UX.

## Deferred from: story-7.1 (2026-06-23)

- **Token streaming + pre-warm** (architecture's ≤15s latency mitigation). 7.1 ships a non-streaming REST call with a "warming up" indicator. Streaming the model's tokens (and pre-warming the model when the Ask-AI panel opens, to hide the ~28s cold-load) only adds value with the real `gemma4:26b` — the dev `MockChatModel` returns instantly — so it's deferred and **belongs with the Mini-side live-model validation** (see `docs/mac-mini-validation.md` §5). Transport would be STOMP (already wired) or SSE.
- **Server-side conversation persistence.** Chat is stateless by design (client resends history; FR-30's "session persists until dismissed" is a client-side session) so no `conversation`/`message` tables and **V20 stays the next free Flyway version**. If saved/searchable threads are ever wanted, that's a new story (+migration).
- **Haiku escalation path is a stub.** 7.1 is `ModelTier.BIG` (local) only; a local-model failure falls through to the `HaikuFallback` stub (`[haiku-fallback-stub] not implemented yet`). Real Anthropic escalation + the "Get deeper analysis" trigger + cost tracking land in **Story 7.3** (FR-32), which must also sanitize context before it leaves the network.

## Deferred from: code review of story-7.1 (2026-06-23)

- **Model failure surfaces as HTTP 200 with the Haiku-stub text (decision deferred to 7.3).** When the real model/Ollama call throws, `DefaultModelGateway.generateBig` swallows the exception and returns `StubHaikuFallback`'s `[haiku-fallback-stub] not implemented yet`, which the chat endpoint returns as a 200 assistant message — so the frontend's error/retry UI never engages and the user sees internal stub text. Pre-existing gateway behavior (Story 1.4), can't occur under the dev mock. **Story 7.3** replaces the stub with the real Haiku fallback; the gateway should additionally translate a true fallback-failure into an error (e.g. 503 Problem Details) rather than returning placeholder text as a success. Until then, document it as a known degraded-mode behavior to validate on the Mini.
- **No client-side timeout / queued-vs-generating feedback** on the concurrency-1 BIG gateway. A queued or cold-loading (~28s) request leaves the panel in "Thinking…" with no upper bound or cancel. Ties to the deferred **token-streaming + pre-warm** work (Mini-relevant) — address together when streaming lands.
- **Silent `MAX_TURNS` (20) truncation.** Long sessions drop the oldest turns from the prompt server-side with no UI indication; coherence can degrade without the user knowing. Add a lightweight "earlier messages trimmed" signal (or summarize) if long Ask-AI sessions become common.
