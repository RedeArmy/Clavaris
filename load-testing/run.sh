#!/usr/bin/env bash
# TD-TEST-004 — see README.md in this directory for what these scenarios measure and why, and for
# the 2026-08-24 baseline results this script's own output should be compared against.
#
# Prerequisites: a running Clavaris instance (java -jar app/target/clavaris-app.jar, or
# docker compose up -d) reachable at CLAVARIS_BASE_URL, and PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET set
# in the environment (same values the running instance itself was started with — see .env.example).
set -euo pipefail

CLAVARIS_BASE_URL="${CLAVARIS_BASE_URL:-http://localhost:8080}"
: "${PLATFORM_BOOTSTRAP_CLIENT_ID:?set to the same value the running instance was started with}"
: "${PLATFORM_BOOTSTRAP_CLIENT_SECRET:?set to the same value the running instance was started with}"

RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"
POST_BODY_FILE="$(mktemp)"
trap 'rm -f "$POST_BODY_FILE"' EXIT
printf 'grant_type=client_credentials' > "$POST_BODY_FILE"

echo "== 1. Baseline capacity: GET /oauth2/jwks (no Argon2, no rate limit) =="
ab -n 5000 -c 100 "${CLAVARIS_BASE_URL}/oauth2/jwks" | tee "${RESULTS_DIR}/jwks-baseline.txt"
ab -n 2000 -c 50 "${CLAVARIS_BASE_URL}/oauth2/jwks" | tee "${RESULTS_DIR}/jwks-c50.txt"
ab -n 10000 -c 300 "${CLAVARIS_BASE_URL}/oauth2/jwks" | tee "${RESULTS_DIR}/jwks-c300.txt"

echo "== 2. POST /oauth2/token under increasing concurrency (real Argon2id verification) =="
echo "   NOTE: raises clavaris.rate-limit.token.per-client-limit on the TARGET instance for the"
echo "   duration of this section, to isolate Argon2/Postgres capacity from rate-limiter"
echo "   enforcement (tested separately in §3 below, at the real default). Restart the instance"
echo "   with CLAVARIS_RATE_LIMIT_TOKEN_PER_CLIENT_LIMIT=100000 before running this section, and"
echo "   without it (the real default, 20) before running §3."
for c in 1 3 10 30; do
  n=$(( c * 10 < 30 ? 30 : c * 10 ))
  ab -n "$n" -c "$c" \
    -A "${PLATFORM_BOOTSTRAP_CLIENT_ID}:${PLATFORM_BOOTSTRAP_CLIENT_SECRET}" \
    -p "$POST_BODY_FILE" -T application/x-www-form-urlencoded \
    "${CLAVARIS_BASE_URL}/oauth2/token" | tee "${RESULTS_DIR}/token-sustained-c${c}.txt"
done

echo "== 3. Rate limiter under real concurrent load (real default: 20 per 5min per client) =="
echo "   Restart the target instance WITHOUT the override above before running this section."
ab -n 50 -c 50 \
  -A "${PLATFORM_BOOTSTRAP_CLIENT_ID}:${PLATFORM_BOOTSTRAP_CLIENT_SECRET}" \
  -p "$POST_BODY_FILE" -T application/x-www-form-urlencoded \
  "${CLAVARIS_BASE_URL}/oauth2/token" | tee "${RESULTS_DIR}/token-ratelimit-burst.txt"
