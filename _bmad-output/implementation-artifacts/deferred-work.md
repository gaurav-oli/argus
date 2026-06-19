# Deferred Work

## Deferred from: code review of story-1.1 (2026-06-18)

- **`mvnw.cmd` line-ending mismatch** — `backend/.gitattributes` declares `*.cmd text eol=crlf`, but the committed `mvnw.cmd` blob is stored LF. On a fresh clone (especially Windows) git will renormalize and show the file as modified / emit "LF will be replaced by CRLF". Deferred: this is stock Spring Initializr output, the project is Mac-only and solo, and the impact is Windows-only. Revisit only if a Windows dev joins.
- ~~**No profile guard on the temporary DB-disabling config**~~ — **RESOLVED by Story 1.2 (2026-06-18):** the `DataSourceAutoConfiguration` exclude and the disabled Redis health indicator were removed when the real Postgres + Redis datasource was wired in. No longer applicable.

## Deferred from: code review of story-1.2 (2026-06-18)

- **PG18 data lives in a major-version subdir inside the volume** — the `pgvector/pgvector:0.8.2-pg18` image stores data at `/var/lib/postgresql/18/docker` within the `argus-pgdata` volume. The FR-40 backup story (Epic 10) must account for this: a future major-version bump (pg19) would create a `19/` subdir in the same volume, so volume-snapshot/`pg_dump` backup tooling must not hardcode the `18/` path. Deferred: relevant only when the backup story is implemented.
