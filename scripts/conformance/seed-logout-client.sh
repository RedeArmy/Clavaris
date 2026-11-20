#!/usr/bin/env bash
# Adds one static client to the conformance Organization made by seed-conformance.sh, for the RP-Initiated
# Logout plan: its post_logout_redirect_uri is the URL the suite listens on.
#
# Run it ON THE HOST, from the deployment directory (it reads that directory's `.env`), and never prints a
# secret; the client goes to $OUT (mode 600). Copy that file to the machine that runs the suite, keep it out of
# Git, and delete it from the host.
#
#   cd /opt/clavaris && ORG_ID=<organization id> OUT=$HOME/logout-client.json bash seed-logout-client.sh
#
# Environment: ORG_ID (required), OUT (required), ALIAS (default clavaris-logout), SUITE (default
# https://localhost.emobix.co.uk:8443), API (default http://127.0.0.1, Caddy on port 80).
set -euo pipefail

ORG_ID="${ORG_ID:?id of the conformance Organization}"
OUT="${OUT:?path of the JSON file to write}"
ALIAS="${ALIAS:-clavaris-logout}"
SUITE="${SUITE:-https://localhost.emobix.co.uk:8443}"
API="${API:-http://127.0.0.1}"

[ -f .env ] || { echo "run this from the deployment directory (no .env here)" >&2; exit 1; }
set -a; . ./.env; set +a
: "${PLATFORM_BOOTSTRAP_CLIENT_ID:?not set in .env}"; : "${PLATFORM_BOOTSTRAP_CLIENT_SECRET:?not set in .env}"

umask 077
urlencode() { python3 -c 'import sys,urllib.parse as u; print(u.quote(sys.argv[1], safe=""))' "$1"; }
BASIC="$(urlencode "$PLATFORM_BOOTSTRAP_CLIENT_ID"):$(urlencode "$PLATFORM_BOOTSTRAP_CLIENT_SECRET")"
TOKEN="$(curl -fsS -u "$BASIC" -d 'grant_type=client_credentials&scope=platform:organizations:write' "${API}/oauth2/token" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"

curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -d "{
  \"redirectUris\":[\"${SUITE}/test/a/${ALIAS}/callback\"],
  \"postLogoutRedirectUris\":[\"${SUITE}/test/a/${ALIAS}/post_logout_redirect\"],
  \"allowedGrantTypes\":[\"authorization_code\",\"refresh_token\"],
  \"allowedScopes\":[\"openid\",\"profile\",\"email\",\"offline_access\"],
  \"requireConsent\":false, \"pkcePolicy\":\"OPTIONAL\"}" \
  "${API}/api/v1/admin/organizations/${ORG_ID}/clients" > "$OUT"
chmod 600 "$OUT"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print("client created:", d.get("clientId") is not None, "post_logout:", d.get("postLogoutRedirectUris"))' "$OUT"
