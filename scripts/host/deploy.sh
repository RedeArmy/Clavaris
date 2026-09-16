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
# Live-found, 2026-09-16: "up -d" itself can fail here, not just the health-poll loop below —
# caddy's own "depends_on: app: condition: service_healthy" (docker-compose.prod.yml) makes Compose
# wait for app's healthcheck internally, and if that wait times out, "up -d" exits non-zero with
# "dependency failed to start: container clavaris-app-1 is unhealthy" *before* app's own healthcheck
# retries/start_period budget (30s + 5x10s = 80s) has necessarily been exhausted from this script's
# perspective, and — under `set -e` — killed this whole script right here: no health-poll loop, no
# rollback, no diagnostic hint, nothing but Compose's own generic dependency error surfaced to CI.
# `|| true` keeps that from aborting the script; app (and its own postgres/redis dependencies) are
# already started by the time only caddy's wait fails, so the poll loop below can still reach it.
if ! docker compose -f "${COMPOSE_FILE}" up -d; then
  echo "'docker compose up -d' itself reported a failure — likely caddy's own dependency wait on" >&2
  echo "app's healthcheck timing out, not app having failed to start at all. Falling through to" >&2
  echo "this script's own health-poll loop instead of trusting that exit code alone." >&2
fi

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
  # app is healthy now, but the earlier "up -d" may be the exact call that failed (the caddy-wait
  # case above) — re-run it so caddy, which was blocked on app's own healthcheck, actually starts
  # too, instead of silently leaving this stack one container short of a working deploy.
  log "Healthy. Starting the remaining services (e.g. caddy, if its own dependency wait blocked it)"
  docker compose -f "${COMPOSE_FILE}" up -d
  log "Deploy complete — now running:"
  docker compose -f "${COMPOSE_FILE}" images app
  exit 0
fi

# Unhealthy within the timeout — this is the "fast doesn't mean unattended-and-broken" half of
# ADR-0018's own reasoning. Dumped automatically, not just referenced by name, so the actual reason
# app never came up is right here in this run's own output — CI has no other way to see it, and
# even on a host, one less round trip than running this by hand after the fact.
echo "App did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s. Its own logs:" >&2
docker compose -f "${COMPOSE_FILE}" logs app --tail=100 >&2 || true

# Only rolls back if there's a real previous image to roll back to (a first-ever deploy with no
# prior version has nothing to fall back to, and should fail loudly instead of silently doing
# nothing).
if [ -z "${PREVIOUS_IMAGE_ID}" ]; then
  fail "No previous image recorded (first deploy?) — nothing to roll back to. See the logs above."
fi

log "Rolling back to the previous image (${PREVIOUS_IMAGE_ID})"
docker tag "${PREVIOUS_IMAGE_ID}" "$(docker compose -f "${COMPOSE_FILE}" config --images app | head -1)"
docker compose -f "${COMPOSE_FILE}" up -d

fail "Deploy failed and was rolled back to the previous image. See the logs above for why the new version didn't come up healthy before retrying."
