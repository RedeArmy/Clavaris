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
          --services) printf 'postgres\nredis\napp\ncaddy\n' ;;
          --images) printf 'postgres:16\nredis:7\nghcr.io/acme/clavaris:latest\ncaddy:2-alpine\n' ;;
        esac ;;
      ps) echo "container123" ;;
      images) echo "sha256:previous" ;;
      exec) [ "${SCENARIO}" = "healthy" ] && exit 0 || exit 1 ;;
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
  LOG="${dir}/calls.log"
  : > "${LOG}"
  (
    cd "${dir}" || exit 99
    PATH="${WORK}/bin:${PATH}" STUB_LOG="${LOG}" SCENARIO="${scenario}" STUB_APP_IMAGE="${app_image}" \
      HEALTH_TIMEOUT_SECONDS=1 HEALTH_POLL_INTERVAL_SECONDS=1 "${DEPLOY}" > "${dir}/out.log" 2>&1
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

echo "state is never recreated"
for scenario in healthy unhealthy; do
  run_deploy "${scenario}"
  # Every "up" must either only ensure the stateful services or leave dependencies alone.
  if grep " up " "${LOG}" | grep -v -e "--no-recreate" -e "--no-deps" > /dev/null; then
    fail "${scenario}: an 'up' could recreate postgres or redis"
  else
    pass "${scenario}: every 'up' is --no-recreate or --no-deps"
  fi
done

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All deploy.sh checks passed."
