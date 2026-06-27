# Argus — Recovery Runbook (Story 10.3, FR-42)

How to rebuild Argus after a failure (disk loss, corrupted DB, Mini replacement). Pairs with the
deploy procedure in [`docs/deploy-runbook.md`](docs/deploy-runbook.md).

> Scope: Argus is a single-user, single-host system on the Mac Mini. Postgres holds all durable state;
> Redis is a cache/stream and is **rebuildable** (sessions, dedup windows, agent streams). The only
> thing you must restore is **Postgres**.

## What is backed up

| Data | Store | Backup | Restore source |
|------|-------|--------|----------------|
| Portfolio, recommendations, outcomes, news/calendar/cost history, briefings, push subs | **Postgres** | `pg_dump` every 6h + critical-table incremental every 15 min to the external SSD (Story 10.1) | latest `pg_dump` |
| Sessions, alert-dedup windows, agent streams | **Redis** | none (ephemeral) | rebuilt on restart |
| Secrets (`.env`: VAPID private key, `ANTHROPIC_API_KEY`, FileVault) | host | your password manager / FileVault | re-entered |
| Local model weights (Gemma via Ollama) | host | re-pullable | `ollama pull` |

> **Automated backups (Story 10.1) are not built yet** — until then, take a manual dump before risky
> changes: `docker compose exec -T postgres pg_dump -U argus argus | gzip > argus-$(date +%F-%H%M).sql.gz`
> and copy it to the external SSD.

## Expected data loss

- With automated backups running: **≤ 15 min** for critical tables, **≤ 6 h** for everything else
  (whatever changed since the last dump).
- Redis loss has no durable impact: you'll be asked to log in again, and agent streams resume from new
  events. The first post-restore agent cycle re-ingests current news/prices.

## Restore procedure

1. **Stand up the host** — follow `docs/deploy-runbook.md` through Docker + native Ollama, but do **not**
   yet rely on app data. Restore secrets into `.env` (VAPID private key must match the committed public
   key, or all existing push subscriptions break — re-subscribe devices if it changed).
2. **Bring up datastores only:**
   ```bash
   docker compose --profile deploy up -d postgres redis
   ```
3. **Restore Postgres** from the latest dump on the external SSD:
   ```bash
   # fresh DB
   docker compose exec -T postgres dropdb   -U argus --if-exists argus
   docker compose exec -T postgres createdb -U argus argus
   gunzip -c /Volumes/<ssd>/argus-backups/<latest>.sql.gz | docker compose exec -T postgres psql -U argus argus
   ```
   Flyway will see the schema already at the dump's version and apply only newer migrations on boot.
4. **Start the rest of the stack:**
   ```bash
   docker compose --profile deploy up -d --build
   ```
5. **Re-pull the model** if the host is new: `ollama pull "$ARGUS_BIG_MODEL"`.
6. **Verify** (see below). Redis starts empty — expected.

## Verification checklist

- [ ] `GET /api/system-info` → `"profile":"prod"`.
- [ ] PIN login works; portfolio holdings + value render (Postgres restored).
- [ ] `GET /api/ops/freshness` shows recent timestamps after the first agent cycle (sources catching up).
- [ ] `GET /api/ops/platform-mode` → `NORMAL` once connectivity is confirmed.
- [ ] A manual `POST /api/briefing/generate` succeeds (model reachable).
- [ ] Re-enable notifications on each device (subscriptions are per-device; only needed if the VAPID
      key changed or `push_subscriptions` was lost).

## Related

- Deploy: `docs/deploy-runbook.md`
- Hardware/runtime validation: `docs/mac-mini-validation.md`
- Backup automation + status: Stories 10.1 / 10.2 (pending on the Mini — external SSD).
