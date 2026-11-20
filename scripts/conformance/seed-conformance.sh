#!/usr/bin/env bash
# Seeds a throwaway Organization for the OpenID conformance plans (docs/05-engineering/openid-conformance-plan.md §4).
#
# Run it ON THE HOST, from the directory that holds the deployment (normally /opt/clavaris): it reads that
# directory's `.env` for the platform bootstrap client and the database credentials, and it never prints a
# secret. Everything the run needs is written to $OUT (mode 600); copy that file to the machine that runs the
# suite, keep it out of Git, and delete it from the host afterwards.
#
#   cd /opt/clavaris && OUT=$HOME/conformance-seed.json bash seed-conformance.sh
#
# What it creates (all new, nothing existing is read back or changed):
#   - one platform operator account that owns the Organization,
#   - the Organization `conformance-<date>-<suffix>`,
#   - two static confidential clients (authorization_code + refresh_token, no consent, pkcePolicy OPTIONAL
#     because the Basic OP plan sends no PKCE),
#   - two end users with a verified email, a name, a username and a phone number (the profile, email and phone
#     scopes need them).
#
# Optional environment:
#   OUT        path of the JSON file to write (required)
#   ALIAS      suite plan alias, used in the clients' redirect URI (default clavaris-basic)
#   SUITE      base URL the suite is served on (default https://localhost.emobix.co.uk:8443)
#   PUBLIC     the issuer's public base URL (default: CLAVARIS_BASE_URL from .env)
#   API        where this host reaches the app (default http://127.0.0.1, Caddy on port 80)
set -euo pipefail

OUT="${OUT:?path of the JSON file to write}"
ALIAS="${ALIAS:-clavaris-basic}"
SUITE="${SUITE:-https://localhost.emobix.co.uk:8443}"
API="${API:-http://127.0.0.1}"

[ -f .env ] || { echo "run this from the deployment directory (no .env here)" >&2; exit 1; }
set -a; . ./.env; set +a
: "${PLATFORM_BOOTSTRAP_CLIENT_ID:?not set in .env}"; : "${PLATFORM_BOOTSTRAP_CLIENT_SECRET:?not set in .env}"
: "${DB_PROD_USER:?not set in .env}"; : "${DB_PROD_NAME:?not set in .env}"
PUBLIC="${PUBLIC:-${CLAVARIS_BASE_URL:?not set in .env}}"
PUBLIC="${PUBLIC%/}"

COMPOSE=(docker compose -f docker-compose.prod.yml)
[ -f docker-compose.preprod.yml ] && COMPOSE+=(-f docker-compose.preprod.yml)
psql_() { "${COMPOSE[@]}" exec -T postgres psql -U "$DB_PROD_USER" -d "$DB_PROD_NAME" -tA "$@"; }

umask 077
SUFFIX="$(openssl rand -hex 3)"
PW() { openssl rand -base64 18 | tr -d '/+=' | cut -c1-20; }
OPERATOR_EMAIL="conf-operator-${SUFFIX}+clavaris_test@example.test"; OPERATOR_PW="Aa1-$(PW)"
USER1_EMAIL="conf-user1-${SUFFIX}+clavaris_test@example.test";     USER1_PW="Aa1-$(PW)"
USER2_EMAIL="conf-user2-${SUFFIX}+clavaris_test@example.test";     USER2_PW="Aa1-$(PW)"

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
urlencode() { python3 -c 'import sys,urllib.parse as u; print(u.quote(sys.argv[1], safe=""))' "$1"; }

register() { # path email password
  local jar csrf; jar="$(mktemp)"
  csrf="$(curl -fsS -c "$jar" -b "$jar" "${API}$1" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)"
  [ -n "$csrf" ] || { rm -f "$jar"; echo "no CSRF token at $1 (is self-registration closed there?)" >&2; exit 1; }
  curl -fsS -o /dev/null -c "$jar" -b "$jar" --data-urlencode "_csrf=${csrf}" --data-urlencode "email=$2" \
    --data-urlencode "password=$3" --data-urlencode "confirmPassword=$3" "${API}$1"
  rm -f "$jar"
}

echo "-- operator" >&2
register /platform/register "$OPERATOR_EMAIL" "$OPERATOR_PW"
OPERATOR_ID="$(psql_ -c "UPDATE platform_accounts SET email_verified_at = now() WHERE email = '${OPERATOR_EMAIL}' RETURNING id" | head -1)"
[ -n "$OPERATOR_ID" ] || { echo "operator not created" >&2; exit 1; }

echo "-- organization" >&2
BASIC="$(urlencode "$PLATFORM_BOOTSTRAP_CLIENT_ID"):$(urlencode "$PLATFORM_BOOTSTRAP_CLIENT_SECRET")"
TOKEN="$(curl -fsS -u "$BASIC" -d 'grant_type=client_credentials&scope=platform:organizations:write' "${API}/oauth2/token" | json_field access_token)"
ORG_ID="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"conformance-$(date +%F)-${SUFFIX}\",\"ownerPlatformAccountId\":\"${OPERATOR_ID}\"}" \
  "${API}/api/v1/admin/organizations" | json_field id)"

echo "-- two clients" >&2
mkclient() {
  curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -d "{
    \"redirectUris\":[\"${SUITE}/test/a/${ALIAS}/callback\"],
    \"allowedGrantTypes\":[\"authorization_code\",\"refresh_token\"],
    \"allowedScopes\":[\"openid\",\"profile\",\"email\",\"phone\",\"offline_access\"],
    \"requireConsent\":false, \"pkcePolicy\":\"OPTIONAL\"}" "${API}/api/v1/admin/organizations/${ORG_ID}/clients"
}
C1="$(mkclient)"; C2="$(mkclient)"

echo "-- two end users" >&2
register "/o/${ORG_ID}/register" "$USER1_EMAIL" "$USER1_PW"
register "/o/${ORG_ID}/register" "$USER2_EMAIL" "$USER2_PW"
psql_ -c "UPDATE accounts SET email_verified_at = now(), first_name='Test', last_name='User One', username='conformance_one', phone_number='+15555550101' WHERE email='${USER1_EMAIL}'" >/dev/null
psql_ -c "UPDATE accounts SET email_verified_at = now(), first_name='Test', last_name='User Two', username='conformance_two', phone_number='+15555550102' WHERE email='${USER2_EMAIL}'" >/dev/null

python3 - "$OUT" <<PY
import json, sys
c1 = json.loads('''$C1'''); c2 = json.loads('''$C2''')
json.dump({
  "orgId": "$ORG_ID", "issuer": "${PUBLIC}/o/${ORG_ID}",
  "discovery": "${PUBLIC}/o/${ORG_ID}/.well-known/openid-configuration",
  "alias": "$ALIAS", "suite": "$SUITE",
  "client1": c1, "client2": c2,
  "user1": {"email": "$USER1_EMAIL", "password": "$USER1_PW", "username": "conformance_one"},
  "user2": {"email": "$USER2_EMAIL", "password": "$USER2_PW", "username": "conformance_two"},
}, open(sys.argv[1], "w"), indent=2)
PY
chmod 600 "$OUT"
echo "wrote $OUT" >&2
echo "issuer: ${PUBLIC}/o/${ORG_ID}"
