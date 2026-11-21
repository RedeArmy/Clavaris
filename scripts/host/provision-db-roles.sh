#!/usr/bin/env bash
# TD-SEC-106: creates the two database roles the application uses (clavaris_owner for migrations,
# clavaris_app for everything else) and brings their grants to the intended state, by running
# provision-roles.sql inside the postgres container as the bootstrap superuser. Idempotent: deploy.sh
# runs it on every deploy, before the application starts, so a password rotated in .env, a table a
# migration added and a partition the maintenance job created are all covered by the next release; an
# operator can also run it by hand after restoring a backup.
#
# Usage: ./provision-db-roles.sh        (from the deployment directory, next to docker-compose.prod.yml)
#
# Reads from .env: DB_PROD_USER, DB_PROD_NAME (the bootstrap superuser and the database), DB_PROD_PASSWORD
# (only to refuse a reuse), DB_APP_PASSWORD and DB_OWNER_PASSWORD.
#
# The passwords go to psql on its standard input, as `\set` lines in front of the SQL, never as command
# line arguments: an argument is visible in the process list of the host while the command runs.
#
# Overridable for the test only: PROVISION_SQL (default provision-roles.sql beside this script, or
# infra/postgres/provision-roles.sql when run from a checkout), PROVISION_WAIT_SECONDS (default 60).

set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
WAIT_SECONDS="${PROVISION_WAIT_SECONDS:-60}"
POLL_SECONDS="${PROVISION_POLL_SECONDS:-2}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() {
  printf '\n\033[1;32m==>\033[0m %s\n' "$1"
}

fail() {
  printf '\n\033[1;31m==> FAILED:\033[0m %s\n' "$1" >&2
  exit 1
}

# Same reader as backup-postgres.sh (TD-PROC-023): the variables this script needs, without running
# the file.
env_value() {
  local line value
  line="$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" .env | tail -n 1)" || true
  value="${line#*=}"
  case "${value}" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
    *) value="${value%% #*}"; value="${value%"${value##*[![:space:]]}"}" ;;
  esac
  printf '%s' "${value}"
}

SQL="${PROVISION_SQL:-}"
if [ -z "${SQL}" ]; then
  if [ -f "${HERE}/provision-roles.sql" ]; then
    SQL="${HERE}/provision-roles.sql"
  elif [ -f "${HERE}/../../infra/postgres/provision-roles.sql" ]; then
    SQL="${HERE}/../../infra/postgres/provision-roles.sql"
  fi
fi
[ -n "${SQL}" ] && [ -f "${SQL}" ] || fail "provision-roles.sql not found next to this script (sync-deploy-files.sh installs it)."
[ -f "${COMPOSE_FILE}" ] || fail "${COMPOSE_FILE} not found — run this from the directory bootstrap.sh set up."
[ -f .env ] || fail ".env not found — DB_PROD_USER, DB_PROD_NAME, DB_APP_PASSWORD and DB_OWNER_PASSWORD must be readable from it."

DB_PROD_USER="$(env_value DB_PROD_USER)"
DB_PROD_NAME="$(env_value DB_PROD_NAME)"
DB_PROD_PASSWORD="$(env_value DB_PROD_PASSWORD)"
DB_APP_PASSWORD="$(env_value DB_APP_PASSWORD)"
DB_OWNER_PASSWORD="$(env_value DB_OWNER_PASSWORD)"
: "${DB_PROD_USER:?DB_PROD_USER must be set in .env}"
: "${DB_PROD_NAME:?DB_PROD_NAME must be set in .env}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD must be set in .env (openssl rand -base64 32)}"
: "${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD must be set in .env (openssl rand -base64 32)}"

# Strong enough, and made of characters that are safe inside a psql single-quoted \set value (no quote,
# no backslash, no whitespace): what `openssl rand -base64 32` produces.
check_password() {
  local name="$1" value="$2"
  if [ "${#value}" -lt 16 ]; then
    fail "${name} is shorter than 16 characters. Use openssl rand -base64 32."
  fi
  if ! [[ "${value}" =~ ^[A-Za-z0-9+/=_.:@%^~-]+$ ]]; then
    fail "${name} has a character this script does not pass to psql safely (allowed: letters, digits and + / = _ . : @ % ^ ~ -). Use openssl rand -base64 32."
  fi
}
check_password DB_APP_PASSWORD "${DB_APP_PASSWORD}"
check_password DB_OWNER_PASSWORD "${DB_OWNER_PASSWORD}"
if [ "${DB_APP_PASSWORD}" = "${DB_OWNER_PASSWORD}" ]; then
  fail "DB_APP_PASSWORD and DB_OWNER_PASSWORD are the same: the point of two roles is two credentials."
fi
if [ -n "${DB_PROD_PASSWORD}" ] && { [ "${DB_APP_PASSWORD}" = "${DB_PROD_PASSWORD}" ] || [ "${DB_OWNER_PASSWORD}" = "${DB_PROD_PASSWORD}" ]; }; then
  fail "A role password is the same as DB_PROD_PASSWORD (the superuser's): the application would hold the superuser's credential after all."
fi

log "Waiting for postgres (up to ${WAIT_SECONDS}s)"
elapsed=0
until docker compose -f "${COMPOSE_FILE}" exec -T postgres pg_isready -q -U "${DB_PROD_USER}" -d "${DB_PROD_NAME}" >/dev/null 2>&1; do
  if [ "${elapsed}" -ge "${WAIT_SECONDS}" ]; then
    fail "postgres did not accept connections within ${WAIT_SECONDS}s. Look at 'docker compose logs postgres'."
  fi
  sleep "${POLL_SECONDS}"
  elapsed=$((elapsed + POLL_SECONDS))
done

log "Provisioning the database roles clavaris_owner (migrations) and clavaris_app (runtime) in ${DB_PROD_NAME}"
{
  printf "\\\\set owner_password '%s'\n" "${DB_OWNER_PASSWORD}"
  printf "\\\\set app_password '%s'\n" "${DB_APP_PASSWORD}"
  cat "${SQL}"
} | docker compose -f "${COMPOSE_FILE}" exec -T postgres \
  psql -X -q -v ON_ERROR_STOP=1 -U "${DB_PROD_USER}" -d "${DB_PROD_NAME}" -f - \
  || fail "provision-roles.sql failed (see above). The application was not started."

# Read back what matters, so a deploy log shows the result and not only the absence of an error.
state="$(docker compose -f "${COMPOSE_FILE}" exec -T postgres \
  psql -X -At -U "${DB_PROD_USER}" -d "${DB_PROD_NAME}" \
  -c "SELECT string_agg(rolname || ':' || rolsuper::text || ':' || rolcreaterole::text || ':' || rolcreatedb::text, ' ' ORDER BY rolname) FROM pg_roles WHERE rolname IN ('clavaris_owner', 'clavaris_app')")" \
  || fail "could not read the roles back."
case "${state}" in
  "clavaris_app:false:false:false clavaris_owner:false:false:false") ;;
  *) fail "The roles are not as intended (rolname:superuser:createrole:createdb): ${state}" ;;
esac
log "Roles in place: ${state}"
