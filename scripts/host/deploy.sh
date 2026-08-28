#!/usr/bin/env bash
# TD-FUT-013 / ADR-0018: the routine "pull latest, restart, confirm it's actually healthy, or roll
# back automatically" cycle — one command, "fast," with a real safety net so "fast" doesn't also
# mean "unattended and silently broken." Run from the deployment directory (bootstrap.sh's own
# DEPLOY_DIR), as the 'clavaris' deploy user, not root — docker compose itself needs no elevated
# privileges once that user is in the docker group.
#
# Usage: ./deploy.sh

set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
HEALTH_URL_INTERNAL="http://localhost:8080/actuator/health/readiness"
HEALTH_TIMEOUT_SECONDS=90
HEALTH_POLL_INTERVAL_SECONDS=3

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

# Captured before pulling anything — the actual rollback target if the new image turns out
# unhealthy, not "whatever :latest happened to be a moment ago" (which could itself already be the
# broken image, if this is a re-run after a failure).
PREVIOUS_IMAGE_ID="$(docker compose -f "${COMPOSE_FILE}" images -q app 2>/dev/null || true)"

log "Pulling latest images"
docker compose -f "${COMPOSE_FILE}" pull

log "Starting the new version"
docker compose -f "${COMPOSE_FILE}" up -d

log "Waiting for the app to report healthy (up to ${HEALTH_TIMEOUT_SECONDS}s)"
elapsed=0
healthy=false
while [ "${elapsed}" -lt "${HEALTH_TIMEOUT_SECONDS}" ]; do
  if docker compose -f "${COMPOSE_FILE}" exec -T app curl -fsS "${HEALTH_URL_INTERNAL}" >/dev/null 2>&1; then
    healthy=true
    break
  fi
  sleep "${HEALTH_POLL_INTERVAL_SECONDS}"
  elapsed=$((elapsed + HEALTH_POLL_INTERVAL_SECONDS))
done

if [ "${healthy}" = true ]; then
  log "Healthy. Deploy complete — now running:"
  docker compose -f "${COMPOSE_FILE}" images app
  exit 0
fi

# Unhealthy within the timeout — this is the "fast doesn't mean unattended-and-broken" half of
# ADR-0018's own reasoning. Only rolls back if there's a real previous image to roll back to (a
# first-ever deploy with no prior version has nothing to fall back to, and should fail loudly
# instead of silently doing nothing).
echo "App did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s." >&2
if [ -z "${PREVIOUS_IMAGE_ID}" ]; then
  fail "No previous image recorded (first deploy?) — nothing to roll back to. Check 'docker compose -f ${COMPOSE_FILE} logs app' by hand."
fi

log "Rolling back to the previous image (${PREVIOUS_IMAGE_ID})"
docker tag "${PREVIOUS_IMAGE_ID}" "$(docker compose -f "${COMPOSE_FILE}" config --images app | head -1)"
docker compose -f "${COMPOSE_FILE}" up -d app

fail "Deploy failed and was rolled back to the previous image. Check 'docker compose -f ${COMPOSE_FILE} logs app' for why the new version didn't come up healthy before retrying."
