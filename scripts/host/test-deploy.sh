#!/usr/bin/env bash
# Tests for deploy.sh with a stand-in "docker" on PATH, so the whole script runs in seconds and every
# command it would have sent to the real daemon is recorded and asserted on. Born from the failed
# deploy of 2026-11-05: the rollback re-tagged the application image as postgres:16, the database
# container then started the application, and nothing in CI could have noticed.
#
# Usage: ./scripts/host/test-deploy.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="${HERE}/deploy.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

# The stand-in daemon. SCENARIO picks the behaviour; every call is appended to STUB_LOG.
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "${STUB_LOG}"
case "$1" in
  tag) exit 0 ;;
  inspect) echo "${STUB_APP_IMAGE-ghcr.io/acme/clavaris:latest}"; exit 0 ;;
  compose)
    shift
    while [ "${1:-}" = "-f" ]; do shift 2; done
    case "$1" in
      config)
        case "${2:-}" in
          -q) [ "${SCENARIO}" = "badenv" ] && { echo "required variable X is missing" >&2; exit 1; }; exit 0 ;;
          --services)
            printf 'postgres\nredis\nredis-counters\napp\ncaddy\n'
            [ -n "${STUB_MONITORING:-}" ] && printf 'prometheus\nalertmanager\nredis-exporter\nredis-counters-exporter\n'
            true ;;
          --images) printf 'postgres:16\nredis:7\nredis:7\nghcr.io/acme/clavaris:latest\ncaddy:2-alpine\n' ;;
        esac ;;
      ps) echo "container123" ;;
      images) echo "sha256:previous" ;;
      exec)
        case "$*" in
          *prometheus*)
            case "$*" in
              *alertmanagers*)
                [ "${STUB_PROM:-up}" = "noam" ] \
                  && echo '{"status":"success","data":{"activeAlertmanagers":[],"droppedAlertmanagers":[]}}' \
                  || echo '{"status":"success","data":{"activeAlertmanagers":[{"url":"http://alertmanager:9093/api/v2/alerts"}]}}' ;;
              *)
                [ "${STUB_PROM:-up}" = "down" ] \
                  && echo '{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1699999999.1,"0"]}]}}' \
                  || echo '{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1699999999.1,"1"]}]}}' ;;
            esac
            exit 0 ;;
        esac
        [ "${SCENARIO}" = "healthy" ] && exit 0 || exit 1 ;;
      pull | up | logs) exit 0 ;;
    esac ;;
esac
exit 0
STUB
chmod +x "${WORK}/bin/docker"

# Runs deploy.sh in a scratch deployment directory; sets RC and leaves the call log in LOG.
run_deploy() {
  local scenario="$1" app_image="${2-ghcr.io/acme/clavaris:latest}"
  local dir="${WORK}/${scenario}"
  mkdir -p "${dir}"
  : > "${dir}/docker-compose.prod.yml"
  rm -rf "${dir}/deploy.last" "${dir}/maintenance.flag" "${dir}/monitoring" "${dir}/docker-compose.monitoring.yml"
  [ -n "${PRE:-}" ] && eval "${PRE}"
  LOG="${dir}/calls.log"
  : > "${LOG}"
  (
    cd "${dir}" || exit 99
    PATH="${WORK}/bin:${PATH}" STUB_LOG="${LOG}" SCENARIO="${scenario}" STUB_APP_IMAGE="${app_image}" \
      HEALTH_TIMEOUT_SECONDS=1 HEALTH_POLL_INTERVAL_SECONDS=1 MONITORING_TIMEOUT_SECONDS=1 MONITORING_POLL_INTERVAL_SECONDS=1 "${DEPLOY}" > "${dir}/out.log" 2>&1
  )
  RC=$?
}

echo "a healthy deploy"
run_deploy healthy
[ "${RC}" -eq 0 ] && pass "succeeds" || fail "exit code ${RC}"
grep -q "^docker tag " "${LOG}" && fail "re-tagged an image although nothing went wrong" || pass "re-tags nothing"
grep -q "up -d --no-deps app" "${LOG}" && pass "replaces only the app" || fail "did not start the app alone"
grep -q "up -d --no-deps caddy" "${LOG}" && pass "starts caddy after the app is healthy" || fail "caddy not started"

echo "an unhealthy new version"
run_deploy unhealthy
[ "${RC}" -ne 0 ] && pass "fails loudly" || fail "reported success"
if grep -qx "docker tag sha256:previous ghcr.io/acme/clavaris:latest" "${LOG}"; then
  pass "rolls back under the application's own image name"
else
  fail "rollback did not tag the previous image as the app image"
fi
grep -E "^docker tag .* (postgres|redis|caddy)" "${LOG}" > /dev/null && fail "re-tagged another service's image" || pass "never re-tags postgres, redis or caddy"

echo "the app container reports a stateful image name"
run_deploy unhealthy "postgres:16"
[ "${RC}" -ne 0 ] && pass "fails" || fail "reported success"
grep -q "^docker tag " "${LOG}" && fail "tagged anyway" || pass "refuses to re-tag"

echo "no container to read the image name from"
run_deploy unhealthy ""
grep -q "^docker tag " "${LOG}" && fail "tagged a guess" || pass "refuses to guess the name"

echo "a compose file the .env does not satisfy"
run_deploy badenv
[ "${RC}" -ne 0 ] && pass "fails" || fail "reported success"
grep -q " pull" "${LOG}" && fail "pulled before checking" || pass "stops before pulling anything"
grep -q " up " "${LOG}" && fail "started something" || pass "starts nothing"

echo "the second Redis is ensured like the other state (TD-SEC-095)"
run_deploy "healthy"
if grep " up " "${LOG}" | grep -e "--no-recreate" | grep -q "redis-counters"; then
  pass "redis-counters is started with --no-recreate"
else
  fail "redis-counters is not among the services ensured with --no-recreate"
fi

echo "state is never recreated"
for scenario in healthy unhealthy; do
  run_deploy "${scenario}"
  # Every "up" must either only ensure the stateful services or leave dependencies alone.
  if grep " up " "${LOG}" | grep -v -e "--no-recreate" -e "--no-deps" > /dev/null; then
    fail "${scenario}: an 'up' could recreate postgres, redis or redis-counters"
  else
    pass "${scenario}: every 'up' is --no-recreate or --no-deps"
  fi
done

echo "planned host maintenance (TD-PROC-027)"
PRE='touch "${dir}/maintenance.flag"' run_deploy healthy
[ "${RC}" -eq 0 ] && pass "exits 0" || fail "exit code ${RC}"
grep -q "NOT DEPLOYED" "${WORK}/healthy/out.log" && pass "says it did not deploy" || fail "silent about skipping"
[ ! -s "${LOG}" ] && pass "sends nothing to docker" || fail "touched docker during maintenance"
PRE='touch -d "5 days ago" "${dir}/maintenance.flag"' run_deploy healthy
grep -q " pull" "${LOG}" && pass "a forgotten flag is ignored" || fail "stale flag still blocked the deploy"

echo "the record of each run (TD-PROC-027)"
run_deploy healthy
grep -q '^START ' "${WORK}/healthy/deploy.last" && grep -q '^END .* status=0' "${WORK}/healthy/deploy.last" \
  && pass "a finished run records START and END status=0" || fail "no complete record"
run_deploy unhealthy
grep -q '^END .* status=1' "${WORK}/unhealthy/deploy.last" && pass "a failed run records its status" || fail "failure not recorded"
PRE='printf "START 2026-01-01T00:00:00Z\n" > "${dir}/deploy.last"' run_deploy healthy
grep -q "never finished" "${WORK}/healthy/out.log" && pass "an interrupted previous run is reported" || fail "interrupted run not reported"

echo "the wait for health outlasts the container's own healthcheck"
COMPOSE="${HERE}/../../docker-compose.prod.yml"
script_timeout="$(sed -n 's/^HEALTH_TIMEOUT_SECONDS="\${HEALTH_TIMEOUT_SECONDS:-\([0-9]*\)}"/\1/p' "${DEPLOY}")"
interval="$(awk '/^  app:/{a=1} a&&/interval:/{gsub(/s/,"",$2);print $2;exit}' "${COMPOSE}")"
retries="$(awk '/^  app:/{a=1} a&&/retries:/{print $2;exit}' "${COMPOSE}")"
start="$(awk '/^  app:/{a=1} a&&/start_period:/{gsub(/s/,"",$2);print $2;exit}' "${COMPOSE}")"
budget=$((start + interval * retries))
if [ -n "${script_timeout}" ] && [ "${script_timeout}" -gt "${budget}" ]; then
  pass "deploy.sh waits ${script_timeout}s, longer than the healthcheck's ${budget}s"
else
  fail "deploy.sh waits ${script_timeout:-?}s but the healthcheck takes ${budget}s to give up"
fi

echo "monitoring that is not set up on the host (TD-PROC-032)"
run_deploy healthy
[ "${RC}" -eq 0 ] && pass "the deploy still succeeds" || fail "exit code ${RC}"
grep -q "MONITORING NOT ENABLED" "${WORK}/healthy/out.log" && pass "says so on every run" || fail "silent about missing monitoring"
grep -q "prometheus" "${LOG}" && fail "touched prometheus without being set up" || pass "starts nothing"

echo "monitoring set up and working"
MON='mkdir -p "${dir}/monitoring"; : > "${dir}/monitoring/alertmanager.yml"; : > "${dir}/docker-compose.monitoring.yml"'
STUB_MONITORING=1 PRE="${MON}" run_deploy healthy
[ "${RC}" -eq 0 ] && pass "succeeds" || fail "exit code ${RC}"
grep -q "docker-compose.monitoring.yml" "${LOG}" && pass "uses the monitoring compose file" || fail "monitoring file not passed to compose"
grep -q "up -d --no-deps prometheus alertmanager redis-exporter redis-counters-exporter" "${LOG}" && pass "starts the monitoring services" || fail "monitoring services not started"
grep " up .*caddy" "${LOG}" | grep -q "prometheus" && fail "caddy's start also started monitoring, unchecked" || pass "the generic start leaves monitoring to the checked step"
grep -q "Monitoring is up" "${WORK}/healthy/out.log" && pass "reports it" || fail "no confirmation"

echo "monitoring that cannot see the application"
STUB_MONITORING=1 STUB_PROM=down PRE="${MON}" run_deploy healthy
[ "${RC}" -eq 3 ] && pass "ends red with its own exit code" || fail "exit code ${RC}, expected 3"
grep -q "^docker tag " "${LOG}" && fail "rolled the application back for a monitoring problem" || pass "does not roll the application back"
grep -q "MONITORING FAILED" "${WORK}/healthy/out.log" && pass "says what is wrong" || fail "no explanation"
grep -q '^END .* status=3' "${WORK}/healthy/deploy.last" && pass "recorded in deploy.last" || fail "not recorded"

echo "monitoring with no Alertmanager"
STUB_MONITORING=1 STUB_PROM=noam PRE="${MON}" run_deploy healthy
[ "${RC}" -eq 3 ] && pass "ends red" || fail "exit code ${RC}, expected 3"

echo "an Alertmanager configuration with a placeholder left in it"
PRE='mkdir -p "${dir}/monitoring"; echo "smtp_auth_password: REPLACE_WITH_A_REAL_RESEND_API_KEY" > "${dir}/monitoring/alertmanager.yml"; : > "${dir}/docker-compose.monitoring.yml"' run_deploy healthy
[ "${RC}" -eq 1 ] && pass "refuses" || fail "exit code ${RC}, expected 1"
grep -q " pull" "${LOG}" && fail "pulled before refusing" || pass "stops before pulling anything"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All deploy.sh checks passed."
