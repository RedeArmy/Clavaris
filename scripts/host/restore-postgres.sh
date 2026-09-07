#!/usr/bin/env bash
# TD-FUT-006: the restore half of backup-postgres.sh — see that script's own header for why both
# the Postgres dump and the signing-key volume archive are restored together, not independently.
#
# DESTRUCTIVE: this replaces the running database's own contents with the backup's, and requires
# briefly stopping the 'app' service. Confirms before doing anything, unless -y is passed (for
# scripted/tested use, e.g. this script's own live-verification run).
#
# Usage:
#   ./restore-postgres.sh [-f compose-file] [-y] <postgres-dump-file> [<signing-keys-tar-file>]
#
# The signing-keys archive argument is optional only because a restore rehearsal against a
# throwaway/staging stack may legitimately want the Postgres half alone — a real production restore
# should always pass both, or every already-issued token becomes unverifiable the moment a consumer
# tries to refresh one (see backup-postgres.sh's own header for the full reasoning).

set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
ASSUME_YES=false
while getopts "f:y" opt; do
  case "${opt}" in
    f) COMPOSE_FILE="${OPTARG}" ;;
    y) ASSUME_YES=true ;;
    *)
      echo "Usage: $0 [-f compose-file] [-y] <postgres-dump-file> [<signing-keys-tar-file>]" >&2
      exit 1
      ;;
  esac
done
shift $((OPTIND - 1))

DUMP_FILE="${1:-}"
KEYS_FILE="${2:-}"
SIGNING_KEYS_VOLUME="clavaris-signing-keys-data"

log() {
  printf '\n\033[1;32m==>\033[0m %s\n' "$1"
}

fail() {
  printf '\n\033[1;31m==> FAILED:\033[0m %s\n' "$1" >&2
  exit 1
}

if [ -z "${DUMP_FILE}" ]; then
  fail "Usage: $0 [-f compose-file] [-y] <postgres-dump-file> [<signing-keys-tar-file>]"
fi
if [ ! -f "${DUMP_FILE}" ]; then
  fail "Dump file not found: ${DUMP_FILE}"
fi
if [ -n "${KEYS_FILE}" ] && [ ! -f "${KEYS_FILE}" ]; then
  fail "Signing-keys archive not found: ${KEYS_FILE}"
fi
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

if [ "${ASSUME_YES}" != true ]; then
  echo "This will REPLACE the contents of database '${DB_PROD_NAME}' on ${COMPOSE_FILE}"
  if [ -n "${KEYS_FILE}" ]; then
    echo "and REPLACE the '${SIGNING_KEYS_VOLUME}' volume's own contents"
  fi
  echo "with the backup at ${DUMP_FILE}${KEYS_FILE:+ / ${KEYS_FILE}}. This cannot be undone."
  read -r -p "Type 'restore' to continue: " confirmation
  if [ "${confirmation}" != "restore" ]; then
    fail "Confirmation not given — aborted, nothing was touched."
  fi
fi

log "Stopping 'app' (no writes may happen mid-restore)"
docker compose -f "${COMPOSE_FILE}" stop app

if [ -n "${KEYS_FILE}" ]; then
  log "Restoring the signing-key material volume (${SIGNING_KEYS_VOLUME})"
  # Clears the volume first — an old key file left behind after a restore-to-an-earlier-point could
  # silently outlive rows that no longer reference it, wasted disk but not a correctness bug, still
  # worth doing cleanly rather than leaving stale material around.
  docker run --rm -v "${SIGNING_KEYS_VOLUME}:/data" alpine:3 sh -c 'rm -rf /data/* /data/.[!.]*' \
    2>/dev/null || true
  docker run --rm \
    -v "${SIGNING_KEYS_VOLUME}:/data" \
    -v "$(cd "$(dirname "${KEYS_FILE}")" && pwd)/$(basename "${KEYS_FILE}"):/backup.tar.gz:ro" \
    alpine:3 \
    tar xzf /backup.tar.gz -C /data
fi

log "Restoring Postgres (${DB_PROD_NAME}) from ${DUMP_FILE}"
docker compose -f "${COMPOSE_FILE}" cp "${DUMP_FILE}" postgres:/tmp/clavaris-restore.dump
# --clean --if-exists: drops existing objects before recreating them (a real restore replaces the
# database's current contents, not merges into them) without failing on a fresh/empty target.
# --no-owner/--no-privileges: the backup's own role names may not exist verbatim on a rebuilt host
# (a fresh VM's own postgres superuser setup), so ownership/grants are skipped rather than failing
# the whole restore over a role that doesn't need to match exactly for the data to be usable.
docker compose -f "${COMPOSE_FILE}" exec -T postgres \
  pg_restore -U "${DB_PROD_USER}" -d "${DB_PROD_NAME}" --clean --if-exists --no-owner --no-privileges \
  /tmp/clavaris-restore.dump
docker compose -f "${COMPOSE_FILE}" exec -T postgres rm -f /tmp/clavaris-restore.dump

log "Restarting 'app'"
docker compose -f "${COMPOSE_FILE}" up -d app

log "Done. Confirm the app reports healthy: docker compose -f ${COMPOSE_FILE} exec -T app curl -fsS http://localhost:8080/actuator/health/readiness"
