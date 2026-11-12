#!/usr/bin/env bash
# Tests for sync-deploy-files.sh against scratch directories (TD-PROC-022).
#
# Usage: ./scripts/host/test-sync-deploy-files.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYNC="${HERE}/sync-deploy-files.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

# A stand-in checkout of the commit being deployed.
SRC="${WORK}/checkout"
mkdir -p "${SRC}/scripts/host"
for f in docker-compose.prod.yml docker-compose.preprod.yml Caddyfile Caddyfile.preprod; do
  echo "new ${f}" > "${SRC}/${f}"
done
printf 'DB_PROD_USER=\nDB_PROD_NAME=\nTOTP_SECRET_ENCRYPTION_KEY=\n' > "${SRC}/.env.example"
echo "new docker-compose.monitoring.yml" > "${SRC}/docker-compose.monitoring.yml"
mkdir -p "${SRC}/infra/observability"
for f in prometheus.yml alert-rules.yml alertmanager.prod.yml.example; do
  echo "new ${f}" > "${SRC}/infra/observability/${f}"
done
for f in deploy.sh backup-postgres.sh restore-postgres.sh; do
  echo "#!/usr/bin/env bash # new ${f}" > "${SRC}/scripts/host/${f}"
done

# A deployment directory: old copies of everything, and an .env that must never change.
new_host() {
  local dir="${WORK}/$1"
  mkdir -p "${dir}"
  for f in docker-compose.prod.yml Caddyfile deploy.sh; do echo "old ${f}" > "${dir}/${f}"; done
  printf 'DB_PROD_USER=clavaris\nDB_PROD_NAME=clavaris_preprod\n' > "${dir}/.env"
  printf 'DB_PROD_USER=\nDB_PROD_NAME=\n' > "${dir}/.env.example"
  echo "${dir}"
}

echo "a production host (no pre-production override)"
PROD="$(new_host production)"
OUT="$("${SYNC}" "${SRC}" "${PROD}" 2>&1)"; RC=$?
[ "${RC}" -eq 0 ] && pass "succeeds" || fail "exit code ${RC}"
[ "$(cat "${PROD}/docker-compose.prod.yml")" = "new docker-compose.prod.yml" ] && pass "refreshes the compose file" || fail "compose file not refreshed"
[ "$(cat "${PROD}/deploy.sh")" != "old deploy.sh" ] && pass "refreshes deploy.sh" || fail "deploy.sh not refreshed"
[ -x "${PROD}/deploy.sh" ] && [ -x "${PROD}/backup-postgres.sh" ] && [ -x "${PROD}/restore-postgres.sh" ] && pass "the scripts are executable" || fail "a script is not executable"
[ -f "${PROD}/backup-postgres.sh" ] && pass "installs the backup scripts it did not have" || fail "backup script missing"
[ ! -e "${PROD}/docker-compose.preprod.yml" ] && [ ! -e "${PROD}/Caddyfile.preprod" ] && pass "never gives a production host the pre-production override" || fail "created a pre-production file"
[ "$(cat "${PROD}/.env")" = "$(printf 'DB_PROD_USER=clavaris\nDB_PROD_NAME=clavaris_preprod')" ] && pass "leaves .env exactly as it was" || fail ".env changed"
grep -q "old docker-compose.prod.yml" <<< "${OUT}" && grep -q "new docker-compose.prod.yml" <<< "${OUT}" && pass "prints what changed" || fail "no diff in the output"
grep -q "TOTP_SECRET_ENCRYPTION_KEY" <<< "${OUT}" && pass "names the variable .env.example has and .env lacks" || fail "did not name the missing variable"
grep -q "clavaris_preprod" <<< "${OUT}" && fail "printed a value from .env" || pass "prints names only, never values"
ls "${PROD}"/*.new > /dev/null 2>&1 && fail "left a temporary file behind" || pass "leaves no temporary file"
[ "$(cat "${PROD}/docker-compose.monitoring.yml")" = "new docker-compose.monitoring.yml" ] && pass "installs the monitoring compose file" || fail "monitoring compose file missing"
[ "$(cat "${PROD}/monitoring/prometheus.yml")" = "new prometheus.yml" ] && [ "$(cat "${PROD}/monitoring/alert-rules.yml")" = "new alert-rules.yml" ] && pass "installs what Prometheus reads" || fail "Prometheus files missing"
[ -f "${PROD}/monitoring/alertmanager.prod.yml.example" ] && pass "installs the Alertmanager template" || fail "template missing"
[ ! -e "${PROD}/monitoring/alertmanager.yml" ] && pass "never creates the Alertmanager configuration, which holds the credential" || fail "created alertmanager.yml"
echo "a host that already has its Alertmanager configuration"
echo "smtp_auth_password: a-real-secret" > "${PROD}/monitoring/alertmanager.yml"
"${SYNC}" "${SRC}" "${PROD}" > /dev/null 2>&1
[ "$(cat "${PROD}/monitoring/alertmanager.yml")" = "smtp_auth_password: a-real-secret" ] && pass "leaves it exactly as it is" || fail "touched alertmanager.yml"

echo "a second run"
OUT2="$("${SYNC}" "${SRC}" "${PROD}" 2>&1)"
grep -q "0 file(s) updated" <<< "${OUT2}" && pass "changes nothing when nothing changed" || fail "not idempotent"

echo "a pre-production host"
PRE="$(new_host preproduction)"
echo "old preprod override" > "${PRE}/docker-compose.preprod.yml"
echo "old preprod caddy" > "${PRE}/Caddyfile.preprod"
"${SYNC}" "${SRC}" "${PRE}" > /dev/null 2>&1
[ "$(cat "${PRE}/docker-compose.preprod.yml")" = "new docker-compose.preprod.yml" ] && pass "refreshes the override it already has" || fail "override not refreshed"
[ "$(cat "${PRE}/Caddyfile.preprod")" = "new Caddyfile.preprod" ] && pass "refreshes Caddyfile.preprod" || fail "Caddyfile.preprod not refreshed"

echo "an incomplete checkout"
BAD="${WORK}/bad-checkout"
cp -r "${SRC}" "${BAD}"; rm "${BAD}/scripts/host/deploy.sh"
HOST="$(new_host incomplete)"
"${SYNC}" "${BAD}" "${HOST}" > /dev/null 2>&1; RC=$?
[ "${RC}" -ne 0 ] && pass "fails when a deployment file is missing" || fail "succeeded with a missing file"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All sync-deploy-files checks passed."
