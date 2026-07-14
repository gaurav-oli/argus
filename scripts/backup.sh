#!/usr/bin/env bash
# Argus Postgres backup (Story 10.1). Usage: backup.sh [full|critical]
# Runs on the HOST (launchd), shelling pg_dump through the compose postgres service.
# Exit codes: 0 ok · 2 dump failed · 3 backup destination not present (disconnected/unmounted)
set -euo pipefail
MODE="${1:-full}"
DEST="${ARGUS_BACKUP_DIR:?set ARGUS_BACKUP_DIR to the backup path}"
PG_SERVICE="${ARGUS_PG_SERVICE:-postgres}"
PG_USER="${POSTGRES_USER:-argus}"
PG_DB="${POSTGRES_DB:-argus}"

# Durable, user-owned tables worth the 15-min cadence: portfolio, decisions, the learning corpus,
# and notification/push state. The 6h full dump covers everything else (ingestion firehose is
# rebuildable and cleanup-pruned anyway).
CRITICAL_TABLES="positions position_lots cash_balances account_meta trade_decisions paper_trades
simulated_trades recommendations recommendation_signals agent_reliability probability_calibration
logic_review agent_graduation watchlist briefings notification_prefs push_subscriptions health_score"

[ -d "$DEST" ] && [ -w "$DEST" ] || { echo "backup dest $DEST missing/not writable (disconnected?)" >&2; exit 3; }
ts="$(date +%Y%m%d-%H%M%S)"

run_dump() { docker compose exec -T "$PG_SERVICE" pg_dump -U "$PG_USER" "$@" "$PG_DB"; }

if [ "$MODE" = "critical" ]; then
  mkdir -p "$DEST/critical"
  tflags=(); for t in $CRITICAL_TABLES; do tflags+=(-t "$t"); done
  out="$DEST/critical/argus-critical-$ts.sql.gz"
  run_dump "${tflags[@]}" | gzip > "$out" || { rm -f "$out"; exit 2; }
  find "$DEST/critical" -name '*.sql.gz' -mmin +180 -delete   # ~3h of 15-min dumps
else
  mkdir -p "$DEST/full"
  out="$DEST/full/argus-$ts.sql.gz"
  run_dump | gzip > "$out" || { rm -f "$out"; exit 2; }
  find "$DEST/full" -name '*.sql.gz' -mtime +14 -delete       # 14 days of 6h dumps
fi
echo "$out"
