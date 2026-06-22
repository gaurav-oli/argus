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
