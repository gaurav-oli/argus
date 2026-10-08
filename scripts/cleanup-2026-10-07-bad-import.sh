#!/usr/bin/env bash
# One-off cleanup of the broken 2026-10-07 statement import (user 1): 57 position rows written with no bank
# or account — currency codes (CAD, USD), a GST number, the holder's surname (OLI), a company name (TESLA),
# and duplicates of real TSLA / ARKK / NIO / RETO holdings with the per-share cost stored as the total.
# Backs every affected row (and its lots) up to backups/ first; aborts if the backup fails. Safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/.."

WHERE="user_id = 1 and institution is null and created_at between '2026-10-07 00:18:00+00' and '2026-10-07 00:20:00+00'"
BACKUP="backups/2026-10-08-bad-import-positions"
PSQL=(docker compose exec -T postgres psql -U argus argus -v ON_ERROR_STOP=1)

echo "Rows matched:"
"${PSQL[@]}" -c "select ticker, count(*) from positions where $WHERE group by ticker order by ticker;"

mkdir -p "$BACKUP"
"${PSQL[@]}" -c "\copy (select * from positions where $WHERE order by id) to stdout with csv header" > "$BACKUP/positions.csv"
"${PSQL[@]}" -c "\copy (select l.* from position_lots l join positions p on p.id = l.position_id where p.$WHERE order by l.id) to stdout with csv header" > "$BACKUP/position_lots.csv"
rows=$(($(wc -l < "$BACKUP/positions.csv") - 1))
echo "Backed up $rows position row(s) and their lots to $BACKUP/"
if [ "$rows" -eq 0 ]; then
  echo "Nothing to clean up."
  exit 0
fi

"${PSQL[@]}" <<SQL
begin;
insert into position_audit (ticker, action, detail, created_at, user_id)
select ticker, 'DELETE',
       'Removed: junk or duplicate row from the broken 2026-10-07 statement import. Backup: $BACKUP',
       now(), user_id
from positions where $WHERE;
update corporate_actions set position_id = null where position_id in (select id from positions where $WHERE);
delete from positions where $WHERE;   -- position_lots cascade
commit;
SQL

echo "Remaining for user 1:"
"${PSQL[@]}" -c "select count(*) as positions, count(*) filter (where ticker in ('CAD','USD','GST','TESLA','ARK','OLI')) as junk_left from positions where user_id = 1;"
echo "Restarting the backend so the live price feed drops the removed symbols..."
docker restart argus-backend >/dev/null && echo "Done."
