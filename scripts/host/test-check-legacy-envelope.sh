#!/usr/bin/env bash
# Tests for check-legacy-envelope.sh (TD-SEC-090): every verdict and exit code, with the database and
# Prometheus replaced by canned answers, and once with a stand-in "docker" to see the real path is
# the one an operator would run. The queries themselves are tested against the real schema by
# LegacyEnvelopeQueriesIntegrationTest.
#
# Usage: ./scripts/host/test-check-legacy-envelope.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${HERE}/check-legacy-envelope.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

# Canned answers: a command that prints a file, and one that fails.
printf 'totp_credentials.secret_encrypted|%s\nwebhook_endpoints.current_or_previous_secret|%s\norganization_social_credentials.client_secret_encrypted|%s\n' 0 0 0 > "${WORK}/clean.txt"
printf 'totp_credentials.secret_encrypted|%s\nwebhook_endpoints.current_or_previous_secret|%s\norganization_social_credentials.client_secret_encrypted|%s\n' 2 0 1 > "${WORK}/dirty.txt"
printf 'this is not a count\n' > "${WORK}/garbled.txt"
printf '{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1700000000.1,"0"]}]}}' > "${WORK}/zero.json"
printf '{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1700000000.1,"3.2"]}]}}' > "${WORK}/reads.json"
printf '{"status":"success","data":{"resultType":"vector","result":[]}}' > "${WORK}/empty.json"
cat > "${WORK}/print-sql-answer" <<STUB
#!/usr/bin/env bash
cat "\${ANSWER}"
STUB
cat > "${WORK}/print-prom-answer" <<STUB
#!/usr/bin/env bash
echo "\$1" >> "\${PROM_LOG}"
cat "\${PROM_ANSWER}"
STUB
cat > "${WORK}/fails" <<'STUB'
#!/usr/bin/env bash
echo "connection refused" >&2
exit 1
STUB
chmod +x "${WORK}"/print-sql-answer "${WORK}"/print-prom-answer "${WORK}"/fails

# run <sql-answer-file|fails> <prom-answer-file|fails|none> [extra env]
run() {
  local sql="$1" prom="$2"
  OUT="${WORK}/out.log"
  : > "${WORK}/prom.log"
  (
    cd "${WORK}" || exit 99
    if [ "${sql}" = fails ]; then export LEGACY_ENVELOPE_PSQL="${WORK}/fails"; else
      export LEGACY_ENVELOPE_PSQL="${WORK}/print-sql-answer" ANSWER="${WORK}/${sql}"; fi
    case "${prom}" in
      none) export LEGACY_ENVELOPE_PROMETHEUS="${WORK}/fails" ;;
      *) export LEGACY_ENVELOPE_PROMETHEUS="${WORK}/print-prom-answer" PROM_ANSWER="${WORK}/${prom}" PROM_LOG="${WORK}/prom.log" ;;
    esac
    "${CHECK}" > "${OUT}" 2>&1
  )
  RC=$?
}

echo "nothing in the old format and the counter at zero"
run clean.txt zero.json
[ "${RC}" -eq 0 ] && pass "exit 0: safe to flip" || fail "exit code ${RC}, expected 0"
grep -q "safe to set CRYPTO_ACCEPT_LEGACY_ENVELOPE=false" "${OUT}" && pass "says so" || fail "no verdict"
grep -q "organization_social_credentials.client_secret_encrypted *0" "${OUT}" && pass "lists every store" || fail "a store is missing from the report"
grep -q 'increase(clavaris_crypto_legacy_envelope_read_total\[14d\])' "${WORK}/prom.log" && pass "asks Prometheus about a release cycle of 14 days" || fail "wrong PromQL: $(cat "${WORK}/prom.log")"

echo "no series at all for the counter (it was never incremented)"
run clean.txt empty.json
[ "${RC}" -eq 0 ] && pass "counts as zero reads" || fail "exit code ${RC}, expected 0"

echo "values still in the old format"
run dirty.txt zero.json
[ "${RC}" -eq 1 ] && pass "exit 1: not yet" || fail "exit code ${RC}, expected 1"
grep -q "NOT YET: 3 value(s)" "${OUT}" && pass "counts them across the stores" || fail "wrong total"
grep -q "totp_credentials.secret_encrypted *2" "${OUT}" && pass "and shows where" || fail "no per-store figure"
[ ! -s "${WORK}/prom.log" ] && pass "does not bother Prometheus once the answer is already no" || fail "queried Prometheus needlessly"

echo "the database is clean but something read an old value recently"
run clean.txt reads.json
[ "${RC}" -eq 1 ] && pass "exit 1: not yet" || fail "exit code ${RC}, expected 1"
grep -q "3.2 read(s)" "${OUT}" && pass "reports the reads" || fail "reads not reported"

echo "the database is clean but there is no monitoring to read the counter from"
run clean.txt none
[ "${RC}" -eq 4 ] && pass "exit 4: half the evidence" || fail "exit code ${RC}, expected 4"
grep -q "second fact is not shown" "${OUT}" && pass "says what is missing" || fail "silent about the missing counter"

echo "the database cannot be queried"
run fails zero.json
[ "${RC}" -eq 2 ] && pass "exit 2" || fail "exit code ${RC}, expected 2"
grep -q "COULD NOT CHECK" "${OUT}" && pass "says so, with the reason" || fail "no explanation"
run garbled.txt zero.json
[ "${RC}" -eq 2 ] && pass "a line that is not a count is an error, not a zero" || fail "exit code ${RC}, expected 2"

echo "the old read path was already switched off on this host"
printf 'CRYPTO_ACCEPT_LEGACY_ENVELOPE=false\n' > "${WORK}/.env"
run dirty.txt zero.json
[ "${RC}" -eq 1 ] && pass "values that cannot be read are an error" || fail "exit code ${RC}, expected 1"
grep -q "already false here, so these 3 value(s) cannot be read" "${OUT}" && pass "and says to put it back" || fail "no warning about unreadable values"
run clean.txt zero.json
[ "${RC}" -eq 0 ] && grep -q "is already false here:" "${OUT}" && grep -q "the old read path is off and nothing needed it" "${OUT}" \
  && pass "off and nothing needed it" || fail "wrong verdict for an already-off host: $(cat "${OUT}")"
rm -f "${WORK}/.env"

echo "the real path (a stand-in docker)"
mkdir -p "${WORK}/bin" "${WORK}/host"
cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "${STUB_LOG}"
case "$*" in
  *exec*postgres*) cat > "${STUB_SQL}"; cat "${STUB_CLEAN}" ;;
  *exec*prometheus*) cat "${STUB_ZERO}" ;;
esac
STUB
chmod +x "${WORK}/bin/docker"
: > "${WORK}/host/docker-compose.prod.yml"
(
  cd "${WORK}/host" || exit 99
  PATH="${WORK}/bin:${PATH}" STUB_LOG="${WORK}/docker.log" STUB_SQL="${WORK}/sent.sql" STUB_CLEAN="${WORK}/clean.txt" STUB_ZERO="${WORK}/zero.json" \
    "${CHECK}" > "${WORK}/out.log" 2>&1
)
RC=$?
[ "${RC}" -eq 4 ] && pass "without the monitoring file the counter is unavailable (exit 4)" || fail "exit code ${RC}, expected 4"
grep -q "docker compose -f docker-compose.prod.yml exec -T postgres sh -c psql" "${WORK}/docker.log" \
  && pass "queries the postgres container with the database's own credentials" || fail "unexpected command: $(cat "${WORK}/docker.log")"
grep -q "legacy_values" "${WORK}/sent.sql" && pass "sends the queries file, not an inline copy" || fail "the SQL was not the file's"
: > "${WORK}/host/docker-compose.monitoring.yml"
(
  cd "${WORK}/host" || exit 99
  PATH="${WORK}/bin:${PATH}" STUB_LOG="${WORK}/docker.log" STUB_SQL="${WORK}/sent.sql" STUB_CLEAN="${WORK}/clean.txt" STUB_ZERO="${WORK}/zero.json" \
    "${CHECK}" > "${WORK}/out.log" 2>&1
)
RC=$?
[ "${RC}" -eq 0 ] && pass "with the monitoring file it reads the counter and is safe (exit 0)" || fail "exit code ${RC}, expected 0"
grep -q "exec -T prometheus" "${WORK}/docker.log" && pass "through the prometheus container" || fail "did not ask the prometheus container"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All check-legacy-envelope.sh checks passed."
