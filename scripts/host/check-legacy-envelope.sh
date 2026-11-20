#!/usr/bin/env bash
# TD-SEC-090: is it safe to set CRYPTO_ACCEPT_LEGACY_ENVELOPE=false on this host?
#
# The old read path in EnvelopeCipher can be removed once two facts hold in every environment that has
# held data: no stored secret is still in the pre-envelope format, and nothing has read one for a full
# release cycle. Both are facts about a host that the repository cannot see, which is why the flag
# stayed on. This script reads both and says which holds; deploy.sh runs it after every deploy, so the
# evidence builds up by itself instead of waiting for someone to remember the three queries.
#
# 1. The database: legacy-envelope-queries.sql, one count per store (the same queries the runbook
#    names, run through psql in the postgres container, whose own POSTGRES_USER and POSTGRES_DB are
#    used so nothing is read from .env).
# 2. The counter clavaris_crypto_legacy_envelope_read_total over LEGACY_ENVELOPE_WINDOW (default 14d,
#    a release cycle), from the Prometheus of docker-compose.monitoring.yml when the host runs it.
#
# Exit codes: 0 both facts hold (safe to flip); 1 a value is still in the old format, or the counter
# moved in the window (not yet); 2 the database could not be queried; 4 the database is clean but the
# counter could not be read (monitoring is off), so only half the evidence exists.
#
# Usage: ./check-legacy-envelope.sh        (from the deployment directory, as the deploy user)
# Overridable, for tests only: LEGACY_ENVELOPE_PSQL (a command that reads SQL on stdin and prints
# store|count rows), LEGACY_ENVELOPE_PROMETHEUS (a command that prints the Prometheus API answer to
# the PromQL query in $1).

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUERIES="${LEGACY_ENVELOPE_QUERIES:-${HERE}/legacy-envelope-queries.sql}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
MONITORING_FILE="docker-compose.monitoring.yml"
WINDOW="${LEGACY_ENVELOPE_WINDOW:-14d}"

[ -f "${QUERIES}" ] || { echo "legacy envelope: ${QUERIES} not found" >&2; exit 2; }

# Whether this host has already switched the old read path off. Read from .env without running it
# (the same care backup-postgres.sh takes), and only to tell the operator what the numbers mean.
flag_is_off=false
if [ -f .env ] && grep -Eq "^[[:space:]]*(export[[:space:]]+)?CRYPTO_ACCEPT_LEGACY_ENVELOPE=[\"']?false[\"']?[[:space:]]*\$" .env; then
  flag_is_off=true
fi

run_sql() {
  if [ -n "${LEGACY_ENVELOPE_PSQL:-}" ]; then
    ${LEGACY_ENVELOPE_PSQL} < "${QUERIES}"
  else
    docker compose -f "${COMPOSE_FILE}" exec -T postgres \
      sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -F "|" -v ON_ERROR_STOP=1' < "${QUERIES}"
  fi
}

prometheus_query() {
  local promql="$1"
  if [ -n "${LEGACY_ENVELOPE_PROMETHEUS:-}" ]; then
    ${LEGACY_ENVELOPE_PROMETHEUS} "${promql}"
    return
  fi
  [ -f "${MONITORING_FILE}" ] || return 1
  local encoded
  encoded="$(printf '%s' "${promql}" | sed 's/%/%25/g; s/ /%20/g; s/{/%7B/g; s/}/%7D/g; s/"/%22/g; s/\[/%5B/g; s/\]/%5D/g; s/(/%28/g; s/)/%29/g')"
  docker compose -f "${COMPOSE_FILE}" -f "${MONITORING_FILE}" exec -T prometheus \
    wget -q -O - "http://localhost:9090/api/v1/query?query=${encoded}" 2>/dev/null
}

echo "Legacy envelope (TD-SEC-090): stored secrets still in the format written before \`v1.<keyId>.\`"

if ! rows="$(run_sql 2>&1)"; then
  echo "  COULD NOT CHECK the database:" >&2
  printf '%s\n' "${rows}" | sed 's/^/    /' >&2
  exit 2
fi

total=0
while IFS='|' read -r store count; do
  [ -n "${store}" ] || continue
  if ! [[ "${count}" =~ ^[0-9]+$ ]]; then
    echo "  COULD NOT CHECK the database: unexpected line '${store}|${count}'" >&2
    exit 2
  fi
  printf '  %-58s %s\n' "${store}" "${count}"
  total=$((total + count))
done <<< "${rows}"

if [ "${total}" -gt 0 ] && [ "${flag_is_off}" = true ]; then
  echo "  WARNING: CRYPTO_ACCEPT_LEGACY_ENVELOPE is already false here, so these ${total} value(s) cannot be read:"
  echo "  a TOTP code, a webhook signature or a social sign-in that needs one fails. Set it back to true and redeploy"
  echo "  (runbook section 6h); the nightly sweep then rewrites them."
  exit 1
fi
if [ "${total}" -gt 0 ]; then
  echo "  NOT YET: ${total} value(s) are still in the old format. Keep CRYPTO_ACCEPT_LEGACY_ENVELOPE=true; the nightly"
  echo "  re-encryption sweep (deployment-runbook.md section 6h) rewrites them, and this check will say when none are left."
  exit 1
fi

reads=""
if answer="$(prometheus_query "sum(increase(clavaris_crypto_legacy_envelope_read_total[${WINDOW}]))")" && [ -n "${answer}" ]; then
  if grep -q '"result":\[\]' <<< "${answer}"; then
    reads=0
  else
    reads="$(sed -n 's/.*"value":\[[0-9.]*,"\([0-9.eE+-]*\)"\].*/\1/p' <<< "${answer}" | head -n 1)"
  fi
fi

if [ -z "${reads}" ]; then
  echo "  The database holds no old-format value, but the read counter could not be read (monitoring is not"
  echo "  running here), so the second fact is not shown. Switch monitoring on (runbook section 7e), or confirm"
  echo "  clavaris_crypto_legacy_envelope_read_total stayed at zero for ${WINDOW} wherever metrics are kept."
  exit 4
fi

# increase() extrapolates, so a single read can show as a fraction: anything above zero counts.
if awk -v r="${reads}" 'BEGIN { exit !(r + 0 > 0) }'; then
  echo "  NOT YET: the counter shows ${reads} read(s) of an old-format value in the last ${WINDOW}, although the"
  echo "  database holds none now. Something wrote or restored one recently; let a full ${WINDOW} pass at zero."
  exit 1
fi

if [ "${flag_is_off}" = true ]; then
  echo "  0 old-format values stored, 0 reads in the last ${WINDOW}, and CRYPTO_ACCEPT_LEGACY_ENVELOPE is already false here:"
  echo "  the old read path is off and nothing needed it. The read branch can be deleted after one release cycle."
else
  echo "  0 old-format values stored, and 0 reads in the last ${WINDOW}: safe to set CRYPTO_ACCEPT_LEGACY_ENVELOPE=false"
  echo "  (runbook section 6h). The read branch can be deleted after one release cycle with it off."
fi
exit 0
