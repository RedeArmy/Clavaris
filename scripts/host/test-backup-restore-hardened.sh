#!/usr/bin/env bash
# Runs the real backup-postgres.sh and restore-postgres.sh against a real Postgres container hardened the
# way docker-compose.prod.yml hardens it (read-only root, /tmp and /var/run/postgresql on tmpfs, every
# capability dropped but the five the entrypoint needs).
#
# Why it exists: test-backup-scripts.sh runs the scripts against a stand-in `docker`, and its stand-in
# `docker cp` copied any file. The scripts used `docker cp` through /tmp, which TD-SEC-106 made a tmpfs,
# and `docker cp` can neither read from a tmpfs nor write to a read-only root. Every check was green and
# the first real backup after the container was recreated failed (pre-production, 2026-11-24). Only a
# real container can say whether a script that talks to one works.
#
# Needs Docker and the image already pinned in docker-compose.prod.yml; skips (exit 0) without Docker.
# The flags below mirror the postgres service of docker-compose.prod.yml: the check at the top of the run
# proves they reproduce the condition (docker cp must be refused), so a mirror that drifted into being
# permissive fails here instead of passing for the wrong reason.
#
# Usage: ./scripts/host/test-backup-restore-hardened.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"

if ! docker info > /dev/null 2>&1; then
  echo "test-backup-restore-hardened.sh: Docker is not available, skipped."
  exit 0
fi

IMAGE="$(grep -E '^[[:space:]]+image: postgres:' "${ROOT}/docker-compose.prod.yml" | head -n 1 | awk '{print $2}')"
if [ -z "${IMAGE}" ]; then
  echo "FAIL could not read the postgres image from docker-compose.prod.yml"
  exit 1
fi

WORK="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="bkphard$$"
KEYS_VOLUME="clavaris-signing-keys-data"
KEYS_VOLUME_EXISTED=false
docker volume inspect "${KEYS_VOLUME}" > /dev/null 2>&1 && KEYS_VOLUME_EXISTED=true

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

cleanup() {
  (cd "${WORK}" && docker compose -f docker-compose.prod.yml down -v --remove-orphans > /dev/null 2>&1)
  # The backup script mounts this volume by name, which creates it when it is absent; leave the host as found.
  if [ "${KEYS_VOLUME_EXISTED}" = false ]; then
    docker volume rm "${KEYS_VOLUME}" > /dev/null 2>&1
  fi
  rm -rf "${WORK}"
}
trap cleanup EXIT

cat > "${WORK}/docker-compose.prod.yml" <<YAML
services:
  postgres:
    image: ${IMAGE}
    cap_drop: [ALL]
    cap_add: [CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID]
    security_opt: ["no-new-privileges:true"]
    read_only: true
    tmpfs: [/tmp, /var/run/postgresql]
    environment:
      POSTGRES_USER: tester
      POSTGRES_PASSWORD: tester-password
      POSTGRES_DB: restore_target
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tester -d restore_target"]
      interval: 2s
      timeout: 3s
      retries: 30
  app:
    image: ${IMAGE}
    command: ["sleep", "infinity"]
YAML
printf 'DB_PROD_USER=tester\nDB_PROD_NAME=restore_target\n' > "${WORK}/.env"
# restore-postgres.sh hands the restored tables to the application's roles; that script has its own tests.
printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/provision-db-roles.sh"

psql_in() { (cd "${WORK}" && docker compose -f docker-compose.prod.yml exec -T postgres psql -U tester -d restore_target -tA "$@"); }

echo "a real hardened postgres container"
(cd "${WORK}" && docker compose -f docker-compose.prod.yml up -d --wait postgres app > "${WORK}/up.log" 2>&1) \
  && pass "starts read-only, on tmpfs, without capabilities" || { fail "did not start: $(tail -3 "${WORK}/up.log")"; exit 1; }

CID="$(cd "${WORK}" && docker compose -f docker-compose.prod.yml ps -q postgres)"
[ "$(docker inspect "${CID}" --format '{{.HostConfig.ReadonlyRootfs}}')" = true ] && pass "the root is read-only" || fail "the root is not read-only"
(cd "${WORK}" && docker compose -f docker-compose.prod.yml exec -T postgres sh -c 'echo x > /tmp/probe') \
  && MSYS_NO_PATHCONV=1 docker cp "${CID}:/tmp/probe" "${WORK}/probe" > /dev/null 2>&1 \
  && fail "docker cp read a file from the tmpfs, so this setup no longer reproduces the condition" \
  || pass "docker cp cannot read from /tmp (the condition that broke the scripts)"

psql_in -c "CREATE TABLE items AS SELECT g AS id, md5(g::text) AS v FROM generate_series(1, 20000) g;" > /dev/null
EXPECTED="$(psql_in -c "SELECT count(*) || ':' || md5(string_agg(id::text || v, ',' ORDER BY id)) FROM items;")"

echo "backup-postgres.sh"
(cd "${WORK}" && bash "${HERE}/backup-postgres.sh" > "${WORK}/backup.log" 2>&1)
[ $? -eq 0 ] && pass "completes against the hardened container" || fail "failed: $(tail -3 "${WORK}/backup.log")"
DUMP="$(ls "${WORK}"/backups/clavaris-postgres-*.dump 2> /dev/null | head -n 1)"
[ -n "${DUMP}" ] && [ -s "${DUMP}" ] && pass "writes a dump that is not empty" || fail "no dump in ${WORK}/backups"
ls "${WORK}"/backups/*.partial > /dev/null 2>&1 && fail "left a .partial file behind" || pass "leaves no temporary file"

echo "restore-postgres.sh"
psql_in -c "DELETE FROM items WHERE id % 2 = 0; INSERT INTO items VALUES (-1, 'not in the backup');" > /dev/null
[ "$(psql_in -c "SELECT count(*) FROM items;")" = 10001 ] && pass "the database was changed after the backup" || fail "could not change the database"
[ -n "${DUMP}" ] && (cd "${WORK}" && bash "${HERE}/restore-postgres.sh" -y "${DUMP}" > "${WORK}/restore.log" 2>&1)
[ $? -eq 0 ] && pass "completes against the hardened container" || fail "failed: $(tail -3 "${WORK}/restore.log")"
ACTUAL="$(psql_in -c "SELECT count(*) || ':' || md5(string_agg(id::text || v, ',' ORDER BY id)) FROM items;")"
[ -n "${EXPECTED}" ] && [ "${ACTUAL}" = "${EXPECTED}" ] && pass "every row is back, and the row that was not in the backup is gone" || fail "expected ${EXPECTED}, found ${ACTUAL}"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All hardened-container backup and restore checks passed."
