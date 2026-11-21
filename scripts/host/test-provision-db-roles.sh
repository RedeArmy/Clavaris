#!/usr/bin/env bash
# Tests for provision-db-roles.sh (TD-SEC-106) with a stand-in "docker" on PATH: what it refuses before it
# touches the database, that the passwords never appear in a command line, and that it fails when the
# roles are not as intended. The SQL itself is tested against a real Postgres by
# DatabaseRolesProvisioningIntegrationTest.
#
# Usage: ./scripts/host/test-provision-db-roles.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${HERE}/provision-db-roles.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

APP_PW='Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa='
OWNER_PW='Bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb='
SU_PW='Ssssssssssssssssssssssssssssssssssssssssss='

mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
# Every call's arguments are logged; standard input of psql is kept, so the test can look at what was sent.
echo "docker $*" >> "${STUB_LOG}"
case "$*" in
  *pg_isready*)
    n=$(($(cat "${STUB_COUNTER}" 2>/dev/null || echo 0) + 1)); echo "${n}" > "${STUB_COUNTER}"
    [ "${n}" -le "${STUB_NOT_READY_FIRST:-0}" ] && exit 2
    exit 0 ;;
  *"-f -"*)
    cat > "${STUB_STDIN}"
    [ -n "${STUB_PSQL_FAIL:-}" ] && exit 3
    exit 0 ;;
  *string_agg*)
    echo "${STUB_ROLES-clavaris_app:false:false:false clavaris_owner:false:false:false}"
    exit 0 ;;
esac
exit 0
STUB
chmod +x "${WORK}/bin/docker"

# prepare <name> <.env body>: a scratch deployment directory; sets DIR, LOG and STDIN.
prepare() {
  DIR="${WORK}/$1"
  mkdir -p "${DIR}"
  : > "${DIR}/docker-compose.prod.yml"
  printf '%s\n' "$2" > "${DIR}/.env"
  printf -- '-- stand-in provision-roles.sql\nSELECT 1;\n' > "${DIR}/provision-roles.sql"
  LOG="${DIR}/calls.log"; STDIN="${DIR}/stdin.txt"
  : > "${LOG}"; : > "${STDIN}"; rm -f "${DIR}/counter"
}

run() {
  (cd "${DIR}" && PATH="${WORK}/bin:${PATH}" STUB_LOG="${LOG}" STUB_STDIN="${STDIN}" STUB_COUNTER="${DIR}/counter" \
    PROVISION_SQL="${DIR}/provision-roles.sql" PROVISION_WAIT_SECONDS=3 PROVISION_POLL_SECONDS=1 \
    bash "${SCRIPT}" > "${DIR}/out.log" 2>&1)
  RC=$?
}

GOOD_ENV="DB_PROD_USER=boot
DB_PROD_NAME=clavaris
DB_PROD_PASSWORD=${SU_PW}
DB_APP_PASSWORD=${APP_PW}
DB_OWNER_PASSWORD=${OWNER_PW}"

echo "a correct .env"
prepare good "${GOOD_ENV}"
run
[ "${RC}" -eq 0 ] && pass "succeeds" || { fail "exit code ${RC}"; cat "${DIR}/out.log"; }
grep -q "^\\\\set owner_password '${OWNER_PW}'$" "${STDIN}" && grep -q "^\\\\set app_password '${APP_PW}'$" "${STDIN}" \
  && pass "sends both passwords to psql on its standard input" || fail "the passwords did not reach psql's standard input"
grep -q "stand-in provision-roles.sql" "${STDIN}" && pass "followed by the SQL file" || fail "the SQL was not sent"
if grep -qF -e "${APP_PW}" -e "${OWNER_PW}" "${LOG}"; then fail "a password appears in a docker command line"; else pass "no password appears in a command line"; fi
grep -q "ON_ERROR_STOP=1" "${LOG}" && pass "psql stops at the first error" || fail "psql would carry on after an error"
grep -q -e "-U boot" "${LOG}" && pass "connects as the bootstrap superuser" || fail "wrong role for the provisioning"

echo "postgres that is not ready at once"
prepare slow "${GOOD_ENV}"
STUB_NOT_READY_FIRST=2 run
[ "${RC}" -eq 0 ] && pass "waits and then succeeds" || fail "exit code ${RC}"
[ "$(grep -c pg_isready "${LOG}")" -ge 3 ] && pass "asked more than once" || fail "did not wait"
prepare never "${GOOD_ENV}"
STUB_NOT_READY_FIRST=99 run
[ "${RC}" -ne 0 ] && pass "gives up when postgres never answers" || fail "reported success"
grep -q "did not accept connections" "${DIR}/out.log" && pass "and says why" || fail "no explanation"

echo "what it refuses before it touches the database"
prepare nopw "DB_PROD_USER=boot
DB_PROD_NAME=clavaris"
run
[ "${RC}" -ne 0 ] && pass "no passwords: refuses" || fail "ran without passwords"
[ ! -s "${STDIN}" ] && ! grep -q "psql" "${LOG}" && pass "and sends nothing to psql" || fail "talked to the database anyway"

prepare same "DB_PROD_USER=boot
DB_PROD_NAME=clavaris
DB_APP_PASSWORD=${APP_PW}
DB_OWNER_PASSWORD=${APP_PW}"
run
[ "${RC}" -ne 0 ] && grep -q "the same" "${DIR}/out.log" && pass "the two passwords equal: refuses" || fail "accepted one password for two roles"

prepare reuse "DB_PROD_USER=boot
DB_PROD_NAME=clavaris
DB_PROD_PASSWORD=${APP_PW}
DB_APP_PASSWORD=${APP_PW}
DB_OWNER_PASSWORD=${OWNER_PW}"
run
[ "${RC}" -ne 0 ] && grep -q "DB_PROD_PASSWORD" "${DIR}/out.log" && pass "the superuser's password reused: refuses" || fail "the application would hold the superuser's password"

prepare short "DB_PROD_USER=boot
DB_PROD_NAME=clavaris
DB_APP_PASSWORD=short
DB_OWNER_PASSWORD=${OWNER_PW}"
run
[ "${RC}" -ne 0 ] && grep -q "shorter than 16" "${DIR}/out.log" && pass "a short password: refuses" || fail "accepted a short password"

prepare quote "DB_PROD_USER=boot
DB_PROD_NAME=clavaris
DB_APP_PASSWORD=aaaaaaaaaaaaaaaaaaaaaaaaa'; DROP
DB_OWNER_PASSWORD=${OWNER_PW}"
run
[ "${RC}" -ne 0 ] && grep -q "safely" "${DIR}/out.log" && pass "a quote in a password: refuses" || fail "a quote reached psql"

echo "a failure of the SQL or a wrong result"
prepare sqlfail "${GOOD_ENV}"
STUB_PSQL_FAIL=1 run
[ "${RC}" -ne 0 ] && grep -q "provision-roles.sql failed" "${DIR}/out.log" && pass "psql fails: the script fails" || fail "went on after a psql error"
prepare badroles "${GOOD_ENV}"
STUB_ROLES='clavaris_app:true:false:false clavaris_owner:false:false:false' run
[ "${RC}" -ne 0 ] && grep -q "not as intended" "${DIR}/out.log" && pass "a role that is a superuser: fails" || fail "accepted a superuser application role"
prepare missingrole "${GOOD_ENV}"
STUB_ROLES='clavaris_owner:false:false:false' run
[ "${RC}" -ne 0 ] && pass "a missing role: fails" || fail "accepted a missing role"

echo
if ((failures > 0)); then
  printf '%d check(s) failed\n' "${failures}"
  exit 1
fi
echo "all provision-db-roles.sh checks passed"
