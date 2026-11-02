#!/usr/bin/env sh
# Daily logical backup of the production database (keep alongside the provider's automated
# snapshots / point-in-time recovery, not instead of them).
#
#   crontab:  30 21 * * *  /opt/cloakroom/deploy/backup-mysql.sh   # 03:00 IST
#
# Credentials come from ~/.my.cnf ([mysqldump] user/password/host), never from this file.
set -eu

DB_NAME="${DB_NAME:-cloak_room_billing}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloakroom}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$BACKUP_DIR/${DB_NAME}_${STAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"
umask 077

# --single-transaction: consistent InnoDB snapshot without locking the app out.
mysqldump --single-transaction --routines --triggers --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 "$DB_NAME" | gzip -9 > "$FILE.tmp"
mv "$FILE.tmp" "$FILE"

# Prune old local copies (ship them off-host, e.g. `aws s3 cp "$FILE" s3://…/`, before pruning).
find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime +"$RETENTION_DAYS" -exec rm -f {} \;

echo "backup written: $FILE"
