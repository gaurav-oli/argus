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
