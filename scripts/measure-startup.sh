#!/usr/bin/env bash
# TD-PERF-064: how long the application takes from process start to a ready readiness probe, and how
# much of that Spring and Flyway account for, so a change to startup (a class-data cache, lazy
# initialisation) is judged by a number and not by a feeling.
#
# Usage: scripts/measure-startup.sh <extracted-dir> [runs] [-- extra java flags...]
#   <extracted-dir>  the output of `java -Djarmode=tools -jar app/target/clavaris-app.jar extract
#                    --layers --destination <dir>`, i.e. the layout the image runs
#   runs             how many cold starts to time (default 3)
# Needs a Postgres and a Redis reachable with the DB_DEV_*/REDIS_* variables below (defaults match
# the e2e job: localhost:5432 / localhost:6379, user, password and database `clavaris`). The
# database is wiped between runs so every run replays all migrations, as a first start does; set
# KEEP_DATABASE=1 to time a restart over an existing schema instead.
set -euo pipefail

dir="${1:?usage: measure-startup.sh <extracted-dir> [runs] [-- java flags]}"
runs="${2:-3}"
shift $(( $# >= 2 ? 2 : 1 ))
[ "${1:-}" = "--" ] && shift
java_flags=("$@")

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export DB_DEV_HOST="${DB_DEV_HOST:-localhost}" DB_DEV_PORT="${DB_DEV_PORT:-5432}"
export DB_DEV_NAME="${DB_DEV_NAME:-clavaris}" DB_DEV_USER="${DB_DEV_USER:-clavaris}"
export DB_DEV_PASSWORD="${DB_DEV_PASSWORD:-clavaris}"
export REDIS_HOST="${REDIS_HOST:-localhost}" REDIS_PORT="${REDIS_PORT:-6379}"
export TOKEN_SIGNING_KEY_STORE_PATH="${TOKEN_SIGNING_KEY_STORE_PATH:-$(mktemp -d)/signing-keys.p12}"
export CLAVARIS_BASE_URL="${CLAVARIS_BASE_URL:-http://localhost:8080}"
export PLATFORM_BOOTSTRAP_CLIENT_ID="${PLATFORM_BOOTSTRAP_CLIENT_ID:-measure-client}"
export PLATFORM_BOOTSTRAP_CLIENT_SECRET="${PLATFORM_BOOTSTRAP_CLIENT_SECRET:-measure-secret-chosen-by-the-operator}"
export PGPASSWORD="$DB_DEV_PASSWORD"
port="${SERVER_PORT:-8080}"

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
for run in $(seq 1 "$runs"); do
  if [ -z "${KEEP_DATABASE:-}" ]; then
    psql -h "$DB_DEV_HOST" -p "$DB_DEV_PORT" -U "$DB_DEV_USER" -d "$DB_DEV_NAME" -qtA -v ON_ERROR_STOP=1 -o /dev/null \
      -c 'SET client_min_messages = warning; DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
  fi
  start=$(date +%s.%N)
  ( cd "$dir" && exec java -Xmx1536m -Xms512m "${java_flags[@]}" -jar clavaris-app.jar ) >"$log" 2>&1 &
  pid=$!
  until curl -fsS -o /dev/null "http://localhost:${port}/actuator/health/readiness" 2>/dev/null; do
    kill -0 "$pid" 2>/dev/null || { tail -30 "$log"; echo "the application exited" >&2; exit 1; }
    sleep 0.2
  done
  end=$(date +%s.%N)
  spring=$(grep -o 'Started [A-Za-z]* in [0-9.]* seconds' "$log" | head -1 || true)
  printf 'run %s: %.1f s to ready; %s\n' "$run" "$(echo "$end - $start" | bc)" "${spring:-no Started line}"
  kill "$pid"; wait "$pid" 2>/dev/null || true
done
