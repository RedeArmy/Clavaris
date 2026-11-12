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
# 150, not 90: the app took 89 seconds to become healthy on the 2-vCPU pre-production host on
# 2026-11-11 and 67 on the next deploy, so 90 left one second of margin. test-deploy.sh holds this
# above the container's own healthcheck budget in docker-compose.prod.yml.
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-150}"
HEALTH_POLL_INTERVAL_SECONDS="${HEALTH_POLL_INTERVAL_SECONDS:-3}"
# TD-PROC-032: how long to give Prometheus to scrape the app and find Alertmanager after the stack
# starts (its first scrape is at most one 15-second interval away).
MONITORING_TIMEOUT_SECONDS="${MONITORING_TIMEOUT_SECONDS:-90}"
MONITORING_POLL_INTERVAL_SECONDS="${MONITORING_POLL_INTERVAL_SECONDS:-3}"

# TD-PROC-021: a deploy replaces the application, never its state. postgres and redis are started if
# they are missing and are otherwise left exactly as they are; recreating them as a side effect of a
# release is how a failed deploy once took the database down with it (see the rollback below).
STATEFUL_SERVICES=(postgres redis redis-counters)

log() {
  printf '\n\033[1;32m==>\033[0m %s\n' "$1"
}

fail() {
  printf '\n\033[1;31m==> FAILED:\033[0m %s\n' "$1" >&2
  exit 1
}

# TD-PROC-032: runs once the application is healthy and caddy is up. A monitoring problem does not
# roll the application back (it is serving), but it is not silent either: the deploy ends red with
# its own exit code, because a host nobody can hear is a failure of the deployment.
prometheus_query() {
  docker compose "${COMPOSE_ARGS[@]}" exec -T prometheus wget -q -O - "$1" 2>/dev/null
}

monitoring_ready() {
  local app_up alertmanagers
  app_up="$(prometheus_query 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22clavaris-app%22%7D')" || return 1
  grep -q '"value":\[[0-9.]*,"1"\]' <<<"${app_up}" || return 1
  alertmanagers="$(prometheus_query 'http://localhost:9090/api/v1/alertmanagers')" || return 1
  grep -q '"activeAlertmanagers":\[{' <<<"${alertmanagers}"
}

ensure_monitoring() {
  if [ "${MONITORING_ENABLED}" != true ]; then
    printf '\n\033[1;33m==> MONITORING NOT ENABLED:\033[0m %s does not exist here, so the alert rules are not being evaluated on this host and nobody would be told if it failed. See deployment-runbook.md section 7e.\n' "${ALERTMANAGER_CONFIG}"
    return 0
  fi
  log "Starting monitoring (${MONITORING_SERVICES[*]})"
  if ! docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps "${MONITORING_SERVICES[@]}"; then
    printf '\n\033[1;31m==> MONITORING FAILED:\033[0m the application is deployed and healthy, but the monitoring services could not be started. No alert will reach anyone until this is fixed.\n' >&2
    exit 3
  fi
  local waited=0
  while [ "${waited}" -lt "${MONITORING_TIMEOUT_SECONDS}" ]; do
    if monitoring_ready; then
      log "Monitoring is up: Prometheus scrapes the application and has found Alertmanager"
      return 0
    fi
    sleep "${MONITORING_POLL_INTERVAL_SECONDS}"
    waited=$((waited + MONITORING_POLL_INTERVAL_SECONDS))
  done
  printf '\n\033[1;31m==> MONITORING FAILED:\033[0m the application is deployed and healthy, but within %ss Prometheus did not report the application target up and an active Alertmanager. No alert will reach anyone until this is fixed (docker compose logs prometheus alertmanager).\n' "${MONITORING_TIMEOUT_SECONDS}" >&2
  exit 3
}

if [ ! -f "${COMPOSE_FILE}" ]; then
  fail "${COMPOSE_FILE} not found — run this from the directory bootstrap.sh set up."
fi

# TD-PROC-027: planned host maintenance. A deploy that starts while the host is being worked on can
# be cut off half way (2026-11-10: the runner lost contact mid-deploy), so an operator who is about to
# do maintenance creates this file and removes it afterwards. While it exists a deploy does nothing and
# exits 0, saying so loudly: the next merge deploys the then-current image anyway, nothing is lost.
# A flag older than MAINTENANCE_MAX_AGE_SECONDS is ignored, so a forgotten one cannot freeze releases.
MAINTENANCE_FLAG="${MAINTENANCE_FLAG:-maintenance.flag}"
MAINTENANCE_MAX_AGE_SECONDS="${MAINTENANCE_MAX_AGE_SECONDS:-172800}"
if [ -f "${MAINTENANCE_FLAG}" ]; then
  flag_age=$(( $(date +%s) - $(stat -c %Y "${MAINTENANCE_FLAG}") ))
  if [ "${flag_age}" -lt "${MAINTENANCE_MAX_AGE_SECONDS}" ]; then
    printf '\n\033[1;33m==> NOT DEPLOYED:\033[0m %s exists (host in planned maintenance). Nothing was pulled or restarted.\n' "${MAINTENANCE_FLAG}"
    echo "Remove the file when maintenance is over and re-run the deploy (deployment-runbook.md section 7d)."
    exit 0
  fi
  echo "Ignoring ${MAINTENANCE_FLAG}: older than ${MAINTENANCE_MAX_AGE_SECONDS}s, presumably forgotten." >&2
fi

# TD-PROC-027: a record of each run, so a deploy that was cut off (host rebooted, runner lost) is
# visible to the next one instead of leaving no trace. START is written now; END only by the exit trap,
# which does not run when the process is killed outright - that missing END is the signal.
DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE:-deploy.last}"
if [ -f "${DEPLOY_STATE_FILE}" ] && ! grep -q '^END ' "${DEPLOY_STATE_FILE}"; then
  printf '\033[1;33m==> WARNING:\033[0m the previous deploy (%s) never finished: it was interrupted. Check the application before trusting its state.\n' "$(sed -n 's/^START //p' "${DEPLOY_STATE_FILE}")" >&2
fi
printf 'START %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${DEPLOY_STATE_FILE}"
record_end() {
  local status=$?
  printf 'END %s status=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${status}" >> "${DEPLOY_STATE_FILE}"
}
trap record_end EXIT

# TD-PROC-022: fail here, before pulling or stopping anything, when the compose file asks for a
# variable the .env does not define. This host keeps its own copy of the compose file, so a newer
# image that needs a new variable used to be found only when the app refused to start.
# TD-PROC-032: the monitoring stack (Prometheus, Alertmanager, the Redis exporters) is part of the
# deployment when the host has been given its Alertmanager configuration, which holds the SMTP
# credential and is therefore created once by the operator (alertmanager.prod.yml.example) and
# never synced. Without it every deploy says so; the alert rules are otherwise read by nobody.
MONITORING_FILE="docker-compose.monitoring.yml"
ALERTMANAGER_CONFIG="monitoring/alertmanager.yml"
MONITORING_SERVICES=(prometheus alertmanager redis-exporter redis-counters-exporter)
MONITORING_ENABLED=false
if [ -f "${MONITORING_FILE}" ] && [ -f "${ALERTMANAGER_CONFIG}" ]; then
  if grep -q 'REPLACE_WITH_' "${ALERTMANAGER_CONFIG}"; then
    fail "${ALERTMANAGER_CONFIG} still has a REPLACE_WITH_ placeholder, so no alert would reach anyone. Nothing was pulled or restarted."
  fi
  COMPOSE_ARGS+=(-f "${MONITORING_FILE}")
  MONITORING_ENABLED=true
fi

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
      app | postgres | redis | redis-counters) ;;
      prometheus | alertmanager | redis-exporter | redis-counters-exporter) ;; # started below, checked
      *) OTHER_SERVICES+=("${service}") ;;
    esac
  done < <(docker compose "${COMPOSE_ARGS[@]}" config --services)
  if [ "${#OTHER_SERVICES[@]}" -gt 0 ]; then
    docker compose "${COMPOSE_ARGS[@]}" up -d --no-deps "${OTHER_SERVICES[@]}"
  fi
  log "Deploy complete — now running:"
  docker compose "${COMPOSE_ARGS[@]}" images app
  ensure_monitoring
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
