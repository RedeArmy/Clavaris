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
# 2026-09-16: pre-production's own Caddy can't own port 443 there anymore — Tailscale Funnel
# already does (this host's own real external HTTPS entry point, replacing ngrok, terminating TLS
# itself and forwarding plain HTTP to this host's port 80). docker-compose.prod.yml itself is never
# touched for this — that file stays production's own real shape (its own domain, Caddy owns TLS
# directly, ADR-0018's original design). Instead, a host opts into the override purely by having
# this second file sitting next to deploy.sh at all — production's own host never will, so this
# array is just [docker-compose.prod.yml] there, unchanged from before this addendum.
COMPOSE_ARGS=(-f "${COMPOSE_FILE}")
PREPROD_OVERRIDE="docker-compose.preprod.yml"
if [ -f "${PREPROD_OVERRIDE}" ]; then
  COMPOSE_ARGS+=(-f "${PREPROD_OVERRIDE}")
fi

HEALTH_URL_INTERNAL="http://localhost:8080/actuator/health/readiness"
# Overridable only so test-deploy.sh can run the whole script in a second; nothing else sets them.
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
HEALTH_POLL_INTERVAL_SECONDS="${HEALTH_POLL_INTERVAL_SECONDS:-3}"

# TD-PROC-021: a deploy replaces the application, never its state. postgres and redis are started if
# they are missing and are otherwise left exactly as they are; recreating them as a side effect of a
# release is how a failed deploy once took the database down with it (see the rollback below).
STATEFUL_SERVICES=(postgres redis)

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

# TD-PROC-022: fail here, before pulling or stopping anything, when the compose file asks for a
# variable the .env does not define. This host keeps its own copy of the compose file, so a newer
# image that needs a new variable used to be found only when the app refused to start.
log "Checking the compose file against the .env"
if ! docker compose "${COMPOSE_ARGS[@]}" config -q; then
  fail "The compose file and the .env disagree (see above). Nothing was pulled or restarted."
fi

# Captured before pulling anything — the actual rollback target if the new image turns out
# unhealthy, not "whatever :latest happened to be a moment ago" (which could itself already be the
# broken image, if this is a re-run after a failure).
PREVIOUS_IMAGE_ID="$(docker compose "${COMPOSE_ARGS[@]}" images -q app 2>/dev/null || true)"

# The name the running app container was created from, read from the container itself. A rollback
# re-tags the previous image under this name. It must never be derived from "config --images",
# which ignores its service argument and lists every service's image in file order: that put
# postgres first and re-tagged the application image as postgres:16 (2026-11-05).
APP_IMAGE_NAME=""
APP_CONTAINER_ID="$(docker compose "${COMPOSE_ARGS[@]}" ps -q app 2>/dev/null || true)"
if [ -n "${APP_CONTAINER_ID}" ]; then
  APP_IMAGE_NAME="$(docker inspect --format '{{.Config.Image}}' "${APP_CONTAINER_ID}" 2>/dev/null || true)"
fi

log "Pulling latest images"
docker compose "${COMPOSE_ARGS[@]}" pull

log "Making sure ${STATEFUL_SERVICES[*]} are running (a deploy never recreates them)"
if ! docker compose "${COMPOSE_ARGS[@]}" up -d --no-recreate "${STATEFUL_SERVICES[@]}"; then
  fail "${STATEFUL_SERVICES[*]} could not be started. Look at 'docker compose logs' for them; the application was not touched."
fi

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
# --no-deps: only the app is replaced here; its dependencies were just ensured above and must not be
# recreated by this call. caddy, which waits on the app's health, is started after the poll below.
if ! docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps app; then
  echo "'docker compose up -d app' itself reported a failure. Falling through to this script's own" >&2
  echo "health-poll loop instead of trusting that exit code alone." >&2
fi

log "Waiting for the app to report healthy (up to ${HEALTH_TIMEOUT_SECONDS}s)"
elapsed=0
healthy=false
while [ "${elapsed}" -lt "${HEALTH_TIMEOUT_SECONDS}" ]; do
  if docker compose "${COMPOSE_ARGS[@]}" exec -T app curl -fsS "${HEALTH_URL_INTERNAL}" >/dev/null 2>&1; then
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
  log "Healthy. Starting the remaining services (e.g. caddy, which waits on the app's health)"
  OTHER_SERVICES=()
  while IFS= read -r service; do
    case "${service}" in
      app | postgres | redis) ;;
      *) OTHER_SERVICES+=("${service}") ;;
    esac
  done < <(docker compose "${COMPOSE_ARGS[@]}" config --services)
  if [ "${#OTHER_SERVICES[@]}" -gt 0 ]; then
    docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps "${OTHER_SERVICES[@]}"
  fi
  log "Deploy complete — now running:"
  docker compose "${COMPOSE_ARGS[@]}" images app
  exit 0
fi

# Unhealthy within the timeout — this is the "fast doesn't mean unattended-and-broken" half of
# ADR-0018's own reasoning. Dumped automatically, not just referenced by name, so the actual reason
# app never came up is right here in this run's own output — CI has no other way to see it, and
# even on a host, one less round trip than running this by hand after the fact.
echo "App did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s. Its own logs:" >&2
docker compose "${COMPOSE_ARGS[@]}" logs app --tail=100 >&2 || true

# Only rolls back if there's a real previous image to roll back to (a first-ever deploy with no
# prior version has nothing to fall back to, and should fail loudly instead of silently doing
# nothing).
if [ -z "${PREVIOUS_IMAGE_ID}" ]; then
  fail "No previous image recorded (first deploy?) — nothing to roll back to. See the logs above."
fi

# Refuse to guess: tagging the previous image under the wrong name once replaced postgres:16 and left
# the database container running the application. Better a failed deploy that says why.
if [ -z "${APP_IMAGE_NAME}" ]; then
  fail "Could not read the image name the app was running under, so the rollback would have to guess. Nothing was re-tagged; restore the previous image by hand (deployment-runbook.md section 4)."
fi
case "${APP_IMAGE_NAME}" in
  postgres[:@]* | redis[:@]* | caddy[:@]*)
    fail "The app container reports its image as '${APP_IMAGE_NAME}', which is not an application image. Nothing was re-tagged."
    ;;
  *) ;;
esac

log "Rolling back to the previous image (${PREVIOUS_IMAGE_ID}) as ${APP_IMAGE_NAME}"
docker tag "${PREVIOUS_IMAGE_ID}" "${APP_IMAGE_NAME}"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps app

fail "Deploy failed and was rolled back to the previous image. See the logs above for why the new version didn't come up healthy before retrying."
