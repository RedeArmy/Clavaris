#!/usr/bin/env bash
# Seeds a running instance for the browser tests and prints the environment to run them with.
#
# Creates: an operator (a platform account that owns the Organization, so the dashboard has something
# to show), one Organization, one OAuth client (authorization_code, no consent screen) and two
# end-user accounts. "+clavaris_test" in an email's local part makes the instance skip every real
# email send for it, so nothing here ever reaches a mail provider. The one thing the HTTP surface
# cannot do is mark the operator's email verified (the link is emailed), so that is a single UPDATE
# through psql (PSQL, default: the docker compose postgres service).
#
# Needs: CLAVARIS_BASE_URL, PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET (as the instance was started),
# curl, python3, and PSQL able to reach its database.
set -euo pipefail

BASE="${CLAVARIS_BASE_URL:-http://localhost:8080}"
: "${PLATFORM_BOOTSTRAP_CLIENT_ID:?set to the value the instance was started with}"
: "${PLATFORM_BOOTSTRAP_CLIENT_SECRET:?set to the value the instance was started with}"
PSQL="${PSQL:-docker compose exec -T postgres psql -U ${DB_DEV_USER:-clavaris} -d ${DB_DEV_NAME:-clavaris} -tA}"
SUFFIX="$(openssl rand -hex 3)"
OPERATOR_EMAIL="e2e-operator-${SUFFIX}+clavaris_test@example.test"
OPERATOR_PASSWORD="E2e-operator-passw0rd-$(openssl rand -hex 4)"
USER_EMAIL_PREFIX="e2e-user-${SUFFIX}+clavaris_test"
USER_PASSWORD="E2e-user-passw0rd-$(openssl rand -hex 4)"
REDIRECT_URI="${BASE}/e2e/callback"

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

register() { # path email password
  local jar csrf
  jar="$(mktemp)"
  csrf="$(curl -fsS -c "$jar" -b "$jar" "${BASE}$1" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)"
  curl -fsS -o /dev/null -c "$jar" -b "$jar" \
    --data-urlencode "_csrf=${csrf}" --data-urlencode "email=$2" \
    --data-urlencode "password=$3" --data-urlencode "confirmPassword=$3" "${BASE}$1"
  rm -f "$jar"
}

echo "-- operator" >&2
register /platform/register "$OPERATOR_EMAIL" "$OPERATOR_PASSWORD"
OPERATOR_ID="$(${PSQL} -c "UPDATE platform_accounts SET email_verified_at = now() WHERE email = '${OPERATOR_EMAIL}' RETURNING id" | head -1)"
[ -n "$OPERATOR_ID" ] || { echo "operator account was not created" >&2; exit 1; }

echo "-- organization and client" >&2
TOKEN="$(curl -fsS -u "${PLATFORM_BOOTSTRAP_CLIENT_ID}:${PLATFORM_BOOTSTRAP_CLIENT_SECRET}" \
  -d 'grant_type=client_credentials&scope=platform:organizations:write' "${BASE}/oauth2/token" | json_field access_token)"
ORG_JSON="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"E2E Co ${SUFFIX}\",\"ownerPlatformAccountId\":\"${OPERATOR_ID}\"}" "${BASE}/api/v1/admin/organizations")"
ORG_ID="$(echo "$ORG_JSON" | json_field id)"
CLIENT_ID="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"redirectUris\":[\"${REDIRECT_URI}\"],\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"],\"requireConsent\":false}" \
  "${BASE}/api/v1/admin/organizations/${ORG_ID}/clients" | json_field clientId)"

echo "-- end users" >&2
for i in 1 2; do register "/o/${ORG_ID}/register" "${USER_EMAIL_PREFIX}${i}@example.test" "$USER_PASSWORD"; done

cat <<OUT
export CLAVARIS_BASE_URL='${BASE}'
export ORG_ID='${ORG_ID}'
export ORG_NAME='E2E Co ${SUFFIX}'
export CLIENT_ID='${CLIENT_ID}'
export REDIRECT_URI='${REDIRECT_URI}'
export USER_EMAIL_PREFIX='${USER_EMAIL_PREFIX}'
export USER_PASSWORD='${USER_PASSWORD}'
export OPERATOR_EMAIL='${OPERATOR_EMAIL}'
export OPERATOR_PASSWORD='${OPERATOR_PASSWORD}'
OUT
