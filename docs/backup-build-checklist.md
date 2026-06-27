# Backup Build Checklist — Stories 10.1 / 10.2 (+ the 9.7 backup half)

Build the automated external-SSD backup + status on the **Mac Mini**, where the SSD actually exists.
Everything here is paste-ready; the only reason it isn't already in `src/` is that it can't be
validated without the SSD + `pg_dump` against a running Postgres. Cross-references:
[`/RECOVERY.md`](../RECOVERY.md) (restore side) and `docs/mac-mini-validation.md §9`.

## Acceptance criteria being satisfied
- **10.1** — critical data backs up every 15 min, full `pg_dump` every 6h; a disconnected SSD raises an
  immediate 🔴 push.
- **10.2** — last success time, size, and SSD health are surfaced; a failure raises a 🟡 notification.
- **9.7 backup half** — the Ops "System health" view shows backup status alongside data freshness.

## Design decisions (rationale)
- **What's backed up:** Postgres only. Redis is a cache/stream (rebuildable); model weights are
  re-pullable; secrets live in `.env` / a password manager. (Same table as `/RECOVERY.md`.)
- **Mechanism:** a shell script (`scripts/backup.sh`) shelling `pg_dump` through the compose service —
  reused by both the scheduler and manual runs, and identical to what the restore runbook expects.
- **"Critical incremental every 15 min":** for a single-user DB (a few MB) true WAL archiving is
  overkill. Pragmatic interpretation: a frequent **critical-table** dump (short retention) + the 6h
  **full** dump (14-day retention). Documented as such so it's an honest mapping, not a silent gap.
- **Status without a new table:** derive status from the filesystem (newest dump mtime + size) + SSD
  path writability + an in-memory last-error. No Flyway migration, and it survives app restarts.
- **Scheduling in-app** (`@Scheduled BackupService`) to match the rest of Argus and reuse the Epic 8
  `NotificationService` for pushes. (Alternative: macOS `launchd` runs the script and the backend only
  reports status — note in "Alternatives" below.)

## Pre-reqs on the Mini
- [ ] External SSD mounted and writable; note its path (macOS: `/Volumes/<NAME>`). Create
      `mkdir -p /Volumes/<NAME>/argus-backups`.
- [ ] `pg_dump` reachable via the compose service: `docker compose exec -T postgres pg_dump --version`.
- [ ] Decide retention (defaults below: full 14 days, critical 3h) and cadences.
- [ ] Add env to the Mini `.env` (see "Config").

---

## Build steps

### 1. Backup script — `scripts/backup.sh`
```bash
#!/usr/bin/env bash
# Argus Postgres backup (Story 10.1). Usage: backup.sh [full|critical]
# Exit codes: 0 ok · 2 dump failed · 3 SSD/dest not present (disconnected)
set -euo pipefail
MODE="${1:-full}"
DEST="${ARGUS_BACKUP_DIR:?set ARGUS_BACKUP_DIR to the SSD backup path}"
PG_SERVICE="${ARGUS_PG_SERVICE:-postgres}"
PG_USER="${POSTGRES_USER:-argus}"
PG_DB="${POSTGRES_DB:-argus}"
# Durable, user-owned tables worth the 15-min cadence:
CRITICAL_TABLES="positions trade_decisions paper_trades recommendations recommendation_signals push_subscriptions briefings agent_graduation health_scores"

[ -d "$DEST" ] && [ -w "$DEST" ] || { echo "backup dest $DEST missing/not writable (SSD disconnected?)" >&2; exit 3; }
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
```
- [ ] Save, `chmod +x scripts/backup.sh`, and smoke-test: `ARGUS_BACKUP_DIR=/Volumes/<NAME>/argus-backups ./scripts/backup.sh full` → a `.sql.gz` appears; `gunzip -t` it.

### 2. `BackupProperties` — `com.argus.backup.BackupProperties`
```java
package com.argus.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("argus.backup")
public record BackupProperties(
        @DefaultValue("") String dir,                 // SSD path; empty disables backups
        @DefaultValue("scripts/backup.sh") String script,
        @DefaultValue("0 0 */6 * * *") String fullCron,
        @DefaultValue("0 */15 * * * *") String criticalCron,
        @DefaultValue("3900") long staleSeconds) {     // 🟡 if newest full dump older than this (~65m)
    public boolean enabled() { return !dir.isBlank(); }
}
```
- [ ] Register with `@EnableConfigurationProperties(BackupProperties.class)` (a `BackupConfig`, mirroring `PushConfig`/`NotificationConfig`).

### 3. `BackupService` — runs dumps, pushes on disconnect/failure
```java
package com.argus.backup;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private final BackupProperties props;
    private final NotificationService notifications;
    private volatile boolean ssdWasConnected = true; // edge-detect disconnect for one-shot 🔴

    public BackupService(BackupProperties props, NotificationService notifications) {
        this.props = props; this.notifications = notifications;
    }

    @Scheduled(cron = "${argus.backup.full-cron:0 0 */6 * * *}", zone = "America/Toronto")
    public void full() { run("full"); }

    @Scheduled(cron = "${argus.backup.critical-cron:0 */15 * * * *}", zone = "America/Toronto")
    public void critical() { run("critical"); }

    void run(String mode) {
        if (!props.enabled()) return;
        boolean connected = new File(props.dir()).canWrite();
        if (!connected) {
            if (ssdWasConnected) {                         // fire 🔴 once on transition
                notify(UrgencyTier.CRITICAL, "🔴 Backup SSD disconnected",
                        "The external backup drive isn't writable — backups are paused.");
            }
            ssdWasConnected = false;
            return;
        }
        ssdWasConnected = true;
        try {
            ProcessBuilder pb = new ProcessBuilder(props.script(), mode);
            pb.environment().put("ARGUS_BACKUP_DIR", props.dir());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(2, TimeUnit.MINUTES);
            int code = done ? p.exitValue() : -1;
            if (code == 3) { /* disconnected mid-run */ ssdWasConnected = false;
                notify(UrgencyTier.CRITICAL, "🔴 Backup SSD disconnected", "Backup drive vanished mid-run.");
            } else if (code != 0) {
                notify(UrgencyTier.IMPORTANT, "🟡 Backup failed", mode + " backup exited " + code + ".");
            } else {
                log.info("{} backup ok", mode);
            }
        } catch (Exception ex) {
            notify(UrgencyTier.IMPORTANT, "🟡 Backup failed", mode + " backup error: " + ex.getMessage());
        }
    }

    private void notify(UrgencyTier tier, String title, String body) {
        try { notifications.notify(Notification.of(tier, title, body, "/agents")); }
        catch (RuntimeException ex) { log.debug("backup notify failed: {}", ex.getMessage()); }
    }
}
```
- [ ] Note: the backend runs in a container in prod — the script shells `docker compose` and writes to the
      SSD. Either run the backend **on the host** for this job, or mount the SSD + docker socket into the
      container, **or** use the `launchd` alternative (below). Pick on the Mini and note it here.

### 4. `BackupStatusService` + `BackupStatusView` — filesystem-derived status (no DB)
```java
package com.argus.backup;

import java.io.File; import java.time.Duration; import java.time.Instant;
import java.util.Arrays; import java.util.Comparator; import java.util.Optional;
import org.springframework.stereotype.Service;

public record BackupStatusView(boolean ssdConnected, Instant lastSuccessAt, Long lastSizeBytes,
        boolean stale, long staleThresholdMinutes) {}

@Service
class BackupStatusService {
    private final BackupProperties props;
    BackupStatusService(BackupProperties props) { this.props = props; }

    BackupStatusView status() {
        File dir = new File(props.dir());
        boolean connected = props.enabled() && dir.canWrite();
        Optional<File> newest = newestDump(new File(dir, "full"));
        Instant last = newest.map(f -> Instant.ofEpochMilli(f.lastModified())).orElse(null);
        Long size = newest.map(File::length).orElse(null);
        boolean stale = last == null
                || Duration.between(last, Instant.now()).getSeconds() > props.staleSeconds();
        return new BackupStatusView(connected, last, size, stale, props.staleSeconds() / 60);
    }

    private static Optional<File> newestDump(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".sql.gz"));
        return files == null ? Optional.empty()
                : Arrays.stream(files).max(Comparator.comparingLong(File::lastModified));
    }
}
```

### 5. Endpoint — add to `OpsController`
```java
// constructor: add BackupStatusService backup
/** Story 10.2 — last backup time/size + SSD health. */
@GetMapping("/backup")
public BackupStatusView backup() { return backup.status(); }
```

### 6. Frontend — backup card in the Ops "System health" section
- [ ] `apiClient.ts`:
```ts
export interface BackupStatusView {
  ssdConnected: boolean; lastSuccessAt: string | null; lastSizeBytes: number | null;
  stale: boolean; staleThresholdMinutes: number;
}
export const getBackupStatus = (): Promise<BackupStatusView> => apiGet("/api/ops/backup");
```
- [ ] Add a `BackupCard` to `frontend/src/features/agents/OpsHealth.tsx` (third card): SSD-connected dot,
      "last backup {relTime}", size (MB), and a 🟡 row when `stale` / 🔴 when `!ssdConnected`. Mirror the
      `FreshnessCard` styling.

### 7. Config
- [ ] `backend/.env` on the Mini:
  ```
  ARGUS_BACKUP_DIR=/Volumes/<NAME>/argus-backups
  # optional overrides:
  # ARGUS_BACKUP_FULL_CRON=0 0 */6 * * *
  # ARGUS_BACKUP_CRITICAL_CRON=0 */15 * * * *
  ```
- [ ] `application.yml` (or `application-prod.yml`) — add an `argus.backup.*` block mirroring the
      `argus.ops`/`argus.resilience` style (bind the env above).

---

## Validate on the Mini (the ACs)
- [ ] **10.1 cadence:** with SSD connected, wait/trigger — a full `.sql.gz` lands in `full/` (6h) and
      critical dumps in `critical/` (15 min); old ones prune per retention.
- [ ] **10.1 disconnect 🔴:** unmount the SSD → within one cycle a **CRITICAL** push fires ("Backup SSD
      disconnected") exactly once (not every tick).
- [ ] **10.2 status:** `GET /api/ops/backup` shows `ssdConnected`, `lastSuccessAt`, `lastSizeBytes`; the
      Ops card renders them.
- [ ] **10.2 failure 🟡:** force a failure (e.g. stop Postgres, run a backup) → an **IMPORTANT** 🟡 push.
- [ ] **Restore drill:** follow `/RECOVERY.md` against the newest dump on a scratch DB; confirm data-loss
      bounds hold. (This also closes the 10.3 recovery-drill item.)
- [ ] Flip `sprint-status.yaml`: `10-1` and `10-2` → done; `9-7` → done; tick the §9 items in
      `mac-mini-validation.md`.

## Alternatives (note which you chose)
- **launchd instead of @Scheduled:** drop a `~/Library/LaunchAgents/com.argus.backup.plist` running
  `scripts/backup.sh` on the two cadences; the backend then only needs `BackupStatusService` + endpoint
  (no `BackupService`). More robust to backend restarts; loses the in-app 🔴/🟡 push on failure (the
  script would need to POST an internal alert endpoint instead). Prefer in-app unless the container/SSD
  mount makes shelling `docker compose` awkward.
- **WAL archiving** (true PITR) if the DB grows or RPO must beat 15 min — heavier; revisit only if needed.
