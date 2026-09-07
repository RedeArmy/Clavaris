#!/usr/bin/env bash
# TD-FUT-006: the "how do we recover if the primary database is lost" story nfr-quality-attributes.md
# §7 flagged as a real, unaddressed gap for a ≥99.5%-availability credential store — this is the
# backup half; scripts/host/restore-postgres.sh is the other. Run from the deployment directory
# (bootstrap.sh's own DEPLOY_DIR), as the 'clavaris' deploy user, on a schedule (see this script's
# own crontab example below) — not just ad hoc before a risky operation.
#
# Backs up BOTH halves a real restore needs together, not just Postgres alone: the
# clavaris-signing-keys-data volume (the PKCS12 signing-key material `signing_keys`/
# `platform_signing_keys` rows only ever reference by kid/metadata, per data-model.md §2 — restoring
# Postgres without this file back in place leaves every Organization's own SigningKey rows pointing
# at key material that no longer exists, a worse, harder-to-diagnose failure than "no backup at all"
# would have been, since it looks like a working database until the first token needs signing).
#
# Usage:
#   ./backup-postgres.sh                       # uses docker-compose.prod.yml, ./backups/
#   ./backup-postgres.sh -f docker-compose.yml # override compose file (e.g. local dev testing)
#
# Suggested crontab entry (as the 'clavaris' user, `crontab -e`) — daily at 02:00, matching
# deployment-runbook.md's own low-traffic-window convention for routine/maintenance operations:
#   0 2 * * * cd /opt/clavaris && ./backup-postgres.sh >> backups/backup.log 2>&1

set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
while getopts "f:" opt; do
  case "${opt}" in
    f) COMPOSE_FILE="${OPTARG}" ;;
    *)
      echo "Usage: $0 [-f compose-file]" >&2
      exit 1
      ;;
  esac
done

BACKUP_DIR="./backups"
# BR-DATA-01 spirit extended to backups: retained on disk only as long as genuinely useful for
# restore, not indefinitely — same "explicit retention, not indefinite growth" posture
# EventOutboxRetentionJob/WebhookDeliveryRetentionJob already establish for this project's own
# tables. Overridable via the environment, not a script argument — a backup's own retention window
# is an operational policy, not something to vary per invocation.
RETENTION_DAYS="${CLAVARIS_BACKUP_RETENTION_DAYS:-14}"
SIGNING_KEYS_VOLUME="clavaris-signing-keys-data"

log() {
  printf '\n\033[1;32m==>\033[0m %s\n' "$1"
}

fail() {
  printf '\n\033[1;31m==> FAILED:\033[0m %s\n' "$1" >&2
  exit 1
}

if [ ! -f "${COMPOSE_FILE}" ]; then
  fail "${COMPOSE_FILE} not found — run this from the directory bootstrap.sh set up."
fi
if [ ! -f .env ]; then
  fail ".env not found — DB_PROD_USER/DB_PROD_NAME must be readable from it."
fi
# shellcheck source=/dev/null
set -a && source .env && set +a

: "${DB_PROD_USER:?DB_PROD_USER must be set in .env}"
: "${DB_PROD_NAME:?DB_PROD_NAME must be set in .env}"

mkdir -p "${BACKUP_DIR}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="${BACKUP_DIR}/clavaris-postgres-${TIMESTAMP}.dump"
KEYS_FILE="${BACKUP_DIR}/clavaris-signing-keys-${TIMESTAMP}.tar.gz"

log "Dumping Postgres (${DB_PROD_NAME}) — custom format, supports parallel/selective restore"
# -F c (custom format), not plain SQL: pg_restore can then run --jobs in parallel and --clean
# --if-exists cleanly, which a plain .sql dump piped through psql can't do as safely.
docker compose -f "${COMPOSE_FILE}" exec -T postgres \
  pg_dump -U "${DB_PROD_USER}" -d "${DB_PROD_NAME}" -F c -f /tmp/clavaris-backup.dump
docker compose -f "${COMPOSE_FILE}" cp postgres:/tmp/clavaris-backup.dump "${DUMP_FILE}"
docker compose -f "${COMPOSE_FILE}" exec -T postgres rm -f /tmp/clavaris-backup.dump

log "Archiving the signing-key material volume (${SIGNING_KEYS_VOLUME})"
# A throwaway alpine container mounting the named volume read-only — the same "don't touch the app
# container for a host-level operation" posture deploy.sh already applies, and works whether or not
# the app container is currently running.
docker run --rm \
  -v "${SIGNING_KEYS_VOLUME}:/data:ro" \
  -v "$(pwd)/${BACKUP_DIR}:/backup" \
  alpine:3 \
  tar czf "/backup/$(basename "${KEYS_FILE}")" -C /data .

DUMP_SIZE="$(du -h "${DUMP_FILE}" | cut -f1)"
KEYS_SIZE="$(du -h "${KEYS_FILE}" | cut -f1)"
log "Backup complete: ${DUMP_FILE} (${DUMP_SIZE}), ${KEYS_FILE} (${KEYS_SIZE})"

log "Pruning backups older than ${RETENTION_DAYS} days"
find "${BACKUP_DIR}" -maxdepth 1 -name 'clavaris-postgres-*.dump' -mtime "+${RETENTION_DAYS}" -print -delete
find "${BACKUP_DIR}" -maxdepth 1 -name 'clavaris-signing-keys-*.tar.gz' -mtime "+${RETENTION_DAYS}" -print -delete

log "Done. See docs/05-engineering/deployment-runbook.md's own backup/restore section for the restore procedure and this pair's own live-verified RPO/RTO numbers."
