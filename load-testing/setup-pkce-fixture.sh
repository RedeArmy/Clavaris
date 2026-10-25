#!/usr/bin/env bash
# Creates what PkceLoginLoadTest.java needs on a running instance: an Organization, one OAuth client
# (authorization_code, no consent screen, so the flow measured is sign-in + code + token and nothing
# else), and N accounts sharing one password. Prints the environment to run the test with.
#
# Prerequisites: a running instance at CLAVARIS_BASE_URL, PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET (the
# values the instance was started with), curl and python3. An Organization needs an owner
# PlatformAccount that exists; there is no HTTP shortcut for that short of the full register +
# verify-email flow, so this inserts one row through psql (PSQL, default: the docker compose
# postgres service, DB_DEV_USER / DB_DEV_NAME from the environment).
set -euo pipefail

CLAVARIS_BASE_URL="${CLAVARIS_BASE_URL:-http://localhost:8080}"
: "${PLATFORM_BOOTSTRAP_CLIENT_ID:?set to the same value the running instance was started with}"
: "${PLATFORM_BOOTSTRAP_CLIENT_SECRET:?set to the same value the running instance was started with}"
ACCOUNTS="${ACCOUNTS:-30}"
# "+clavaris_test" in the local part makes the instance skip every real email send for the address
# (TestEmailAddress), so registering and signing in never reaches a mail provider.
USER_EMAIL_PREFIX="${USER_EMAIL_PREFIX:-load+clavaris_test}"
USER_PASSWORD="${USER_PASSWORD:-Load-test-passw0rd-$(openssl rand -hex 4)}"
REDIRECT_URI="${REDIRECT_URI:-https://client.example.test/callback}"
PSQL="${PSQL:-docker compose exec -T postgres psql -U ${DB_DEV_USER:-clavaris} -d ${DB_DEV_NAME:-clavaris} -tA}"

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

echo "-- platform token" >&2
PLATFORM_TOKEN="$(curl -fsS -u "${PLATFORM_BOOTSTRAP_CLIENT_ID}:${PLATFORM_BOOTSTRAP_CLIENT_SECRET}" \
  -d 'grant_type=client_credentials&scope=platform:organizations:write' \
  "${CLAVARIS_BASE_URL}/oauth2/token" | json_field access_token)"

echo "-- owner platform account" >&2
# One statement, so the database's deferred "an account needs an auth method" check (BR-ID-02) sees the
# credential at commit. The hash is not a real one: nobody ever signs in as this owner.
OWNER_ID="$(${PSQL} -c "WITH a AS (
    INSERT INTO platform_accounts (id, email, email_verified_at, status)
    VALUES (gen_random_uuid(), 'load-owner-$(openssl rand -hex 4)@example.test', now(), 'ACTIVE')
    RETURNING id),
  c AS (
    INSERT INTO platform_password_credentials (platform_account_id, password_hash)
    SELECT id, 'not-a-real-hash-this-owner-never-signs-in' FROM a)
  SELECT id FROM a" | head -1)"

echo "-- organization" >&2
ORG_ID="$(curl -fsS -H "Authorization: Bearer ${PLATFORM_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Load Test Co $(openssl rand -hex 3)\",\"ownerPlatformAccountId\":\"${OWNER_ID}\"}" \
  "${CLAVARIS_BASE_URL}/api/v1/admin/organizations" | json_field id)"

echo "-- oauth client" >&2
CLIENT_JSON="$(curl -fsS -H "Authorization: Bearer ${PLATFORM_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"redirectUris\":[\"${REDIRECT_URI}\"],\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"],\"requireConsent\":false}" \
  "${CLAVARIS_BASE_URL}/api/v1/admin/organizations/${ORG_ID}/clients")"
CLIENT_ID="$(echo "${CLIENT_JSON}" | json_field clientId)"
CLIENT_SECRET="$(echo "${CLIENT_JSON}" | json_field clientSecret)"

echo "-- machine-to-machine oauth client (client_credentials, for the token-endpoint scenario)" >&2
M2M_JSON="$(curl -fsS -H "Authorization: Bearer ${PLATFORM_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"redirectUris\":[\"${REDIRECT_URI}\"],\"allowedGrantTypes\":[\"client_credentials\"],\"allowedScopes\":[\"openid\"],\"requireConsent\":false}" \
  "${CLAVARIS_BASE_URL}/api/v1/admin/organizations/${ORG_ID}/clients")"
M2M_CLIENT_ID="$(echo "${M2M_JSON}" | json_field clientId)"
M2M_CLIENT_SECRET="$(echo "${M2M_JSON}" | json_field clientSecret)"

echo "-- ${ACCOUNTS} accounts" >&2
JAR="$(mktemp)"
trap 'rm -f "${JAR}"' EXIT
for i in $(seq 1 "${ACCOUNTS}"); do
  : >"${JAR}"
  CSRF="$(curl -fsS -c "${JAR}" -b "${JAR}" "${CLAVARIS_BASE_URL}/o/${ORG_ID}/register" \
    | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)"
  curl -fsS -o /dev/null -c "${JAR}" -b "${JAR}" \
    --data-urlencode "_csrf=${CSRF}" \
    --data-urlencode "email=${USER_EMAIL_PREFIX}${i}@example.test" \
    --data-urlencode "password=${USER_PASSWORD}" \
    --data-urlencode "confirmPassword=${USER_PASSWORD}" \
    "${CLAVARIS_BASE_URL}/o/${ORG_ID}/register"
done

cat <<EOF
export CLAVARIS_BASE_URL='${CLAVARIS_BASE_URL}'
export ORG_ID='${ORG_ID}'
export CLIENT_ID='${CLIENT_ID}'
export CLIENT_SECRET='${CLIENT_SECRET}'
export M2M_CLIENT_ID='${M2M_CLIENT_ID}'
export M2M_CLIENT_SECRET='${M2M_CLIENT_SECRET}'
export REDIRECT_URI='${REDIRECT_URI}'
export USER_EMAIL_PREFIX='${USER_EMAIL_PREFIX}'
export USER_PASSWORD='${USER_PASSWORD}'
export ACCOUNTS='${ACCOUNTS}'
EOF
