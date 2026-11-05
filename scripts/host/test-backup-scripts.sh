#!/usr/bin/env bash
# Tests for backup-postgres.sh and restore-postgres.sh with a stand-in "docker" on PATH. They used to
# load the whole .env with `source`, so a value Docker Compose accepts (JAVA_OPTS=-Xmx1536m -Xms512m)
# made bash try to run "-Xms512m", and a value with $ or a backtick was expanded (TD-PROC-023).
#
# Usage: ./scripts/host/test-backup-scripts.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "${STUB_LOG}"
case "$1" in
  run)
    # the signing-keys archive: create the file the script then measures
    host_dir=""; name=""
    for arg in "$@"; do
      case "${arg}" in
        *:/backup) host_dir="${arg%%:/backup}" ;;
        /backup/*) name="${arg#/backup/}" ;;
      esac
    done
    [ -n "${host_dir}" ] && [ -n "${name}" ] && echo archive > "${host_dir}/${name}"
    ;;
  compose)
    shift
    while [ "${1:-}" = "-f" ]; do shift 2; done
    case "$1" in
      cp)
        # "cp postgres:/tmp/x DEST" (backup) creates DEST; "cp SRC postgres:/tmp/x" (restore) does not
        case "$2" in postgres:*) echo dump > "$3" ;; esac ;;
    esac ;;
esac
exit 0
STUB
chmod +x "${WORK}/bin/docker"

# Prepares a scratch deployment directory with the given .env body; sets DIR and LOG.
prepare() {
  DIR="${WORK}/$1"
  mkdir -p "${DIR}"
  : > "${DIR}/docker-compose.prod.yml"
  printf '%s\n' "${2}" > "${DIR}/.env"
  LOG="${DIR}/calls.log"
  : > "${LOG}"
}

run() {
  (cd "${DIR}" && PATH="${WORK}/bin:${PATH}" STUB_LOG="${LOG}" "$@" > "${DIR}/out.log" 2>&1)
  RC=$?
}

# An .env that Docker Compose reads fine and bash cannot source: an unquoted value with a space, a
# value with $ and a backtick, a quoted value, an inline comment, and an assignment repeated.
ENV_BODY='JAVA_OPTS=-Xmx1536m -Xms512m
SECRET=pa$word`whoami`
DB_PROD_USER=unused
DB_PROD_USER=clavaris # the deploy user
DB_PROD_NAME="clavaris_preprod"'

echo "backup-postgres.sh"
prepare backup "${ENV_BODY}"
run "${HERE}/backup-postgres.sh"
[ "${RC}" -eq 0 ] && pass "succeeds with an .env bash cannot source" || fail "exit code ${RC}: $(tail -3 "${DIR}/out.log")"
grep -q "command not found" "${DIR}/out.log" && fail "ran part of the .env as a command" || pass "runs nothing from the .env"
grep -q "pg_dump -U clavaris -d clavaris_preprod" "${LOG}" && pass "reads the last assignment, unquoted, comment dropped" || fail "wrong user or database: $(grep pg_dump "${LOG}")"
ls "${DIR}"/backups/clavaris-postgres-*.dump > /dev/null 2>&1 && pass "writes the dump" || fail "no dump written"

prepare nouser 'DB_PROD_NAME=clavaris_preprod'
run "${HERE}/backup-postgres.sh"
[ "${RC}" -ne 0 ] && grep -q "DB_PROD_USER must be set" "${DIR}/out.log" && pass "names a missing variable" || fail "did not name DB_PROD_USER"
grep -q "pg_dump" "${LOG}" && fail "dumped without a user" || pass "touches nothing when a variable is missing"

echo "restore-postgres.sh"
prepare restore "${ENV_BODY}"
echo dump > "${DIR}/some.dump"
run "${HERE}/restore-postgres.sh" -y some.dump
[ "${RC}" -eq 0 ] && pass "succeeds with an .env bash cannot source" || fail "exit code ${RC}: $(tail -3 "${DIR}/out.log")"
grep -q "command not found" "${DIR}/out.log" && fail "ran part of the .env as a command" || pass "runs nothing from the .env"
grep -q "pg_restore -U clavaris -d clavaris_preprod" "${LOG}" && pass "restores as the right user into the right database" || fail "wrong user or database"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All backup script checks passed."
