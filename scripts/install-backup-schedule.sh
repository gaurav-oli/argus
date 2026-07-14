#!/usr/bin/env bash
# Install (or reinstall) the launchd schedule for Argus backups (Story 10.1).
# Host-side because the backend runs in a container and can't docker-exec pg_dump itself:
# launchd runs scripts/backup.sh; the backend only READS the backup dir (mounted ro) for status.
#   full backup    — every 6 hours
#   critical dump  — every 15 minutes (durable user tables only, ~3h retention)
# Usage: ARGUS_BACKUP_DIR=/Users/leanna/argus-backups ./scripts/install-backup-schedule.sh
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="${ARGUS_BACKUP_DIR:-$HOME/argus-backups}"
AGENTS_DIR="$HOME/Library/LaunchAgents"
mkdir -p "$BACKUP_DIR" "$AGENTS_DIR"

write_plist() { # $1 label  $2 mode  $3 interval-seconds
  local plist="$AGENTS_DIR/$1.plist"
  cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$1</string>
  <key>ProgramArguments</key>
  <array>
    <string>$REPO_DIR/scripts/backup.sh</string>
    <string>$2</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ARGUS_BACKUP_DIR</key><string>$BACKUP_DIR</string>
    <key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string>
  </dict>
  <key>WorkingDirectory</key><string>$REPO_DIR</string>
  <key>StartInterval</key><integer>$3</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$BACKUP_DIR/launchd-$2.log</string>
  <key>StandardErrorPath</key><string>$BACKUP_DIR/launchd-$2.log</string>
</dict>
</plist>
EOF
  launchctl unload "$plist" 2>/dev/null || true
  launchctl load "$plist"
  echo "loaded $1 ($2 every ${3}s) → $BACKUP_DIR"
}

write_plist "com.argus.backup.full" "full" 21600        # 6h
write_plist "com.argus.backup.critical" "critical" 900  # 15min
echo "Done. Verify: launchctl list | grep com.argus.backup ; ls $BACKUP_DIR/{full,critical}"
