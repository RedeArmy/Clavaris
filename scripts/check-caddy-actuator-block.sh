#!/usr/bin/env bash
# Proves the real ./Caddyfile never forwards /actuator/** to the application — on the primary
# domain and on a per-client custom domain written the way deployment-runbook.md §9e prescribes —
# and still forwards everything else. Runs the exact Caddy image docker-compose.prod.yml uses, in
# front of a stub upstream aliased "app:8080" (the Caddyfile's own upstream), so the file under test
# is the production one, not a copy that can drift. Requires only Docker and curl.
#
# Why this exists: Caddyfile syntax errors and matcher gaps (";param" segments, case, encoding,
# duplicate slashes, a copied site block that forgot the rule) fail silently — the proxy keeps
# starting and the endpoint stays public.
set -euo pipefail

cd "$(dirname "$0")/.."

# Overridable only so the check can be proven to fail against a Caddyfile that lacks the block.
CADDYFILE="${CADDYFILE:-$PWD/Caddyfile}"

IMAGE="$(grep -E '^\s+image:\s+caddy:' docker-compose.prod.yml | head -1 | awk '{print $2}')"
if [ -z "${IMAGE}" ]; then
  echo "FAIL: could not read the caddy image tag from docker-compose.prod.yml" >&2
  exit 2
fi

SUFFIX="$$-$(date +%s)"
NETWORK="caddy-actuator-check-${SUFFIX}"
UPSTREAM="caddy-actuator-upstream-${SUFFIX}"
PROXY="caddy-actuator-proxy-${SUFFIX}"
WORKDIR="$(mktemp -d)"
BODY_FILE="${WORKDIR}/body"

cleanup() {
  docker rm -f "${PROXY}" "${UPSTREAM}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  rm -rf "${WORKDIR}"
}
trap cleanup EXIT

# The Caddyfile under test, plus a custom-domain site written as the runbook tells an operator to:
# one line importing the shared snippet. If that snippet is renamed or removed the proxy fails to
# start, which fails this check loudly rather than silently dropping the custom-domain coverage.
cp "${CADDYFILE}" "${WORKDIR}/Caddyfile"
cat >>"${WORKDIR}/Caddyfile" <<'EOF'

http://custom.example:8082 {
	import clavaris_edge
}
EOF

docker network create "${NETWORK}" >/dev/null

# Stub for the application: answers every path with 200 "upstream-ok", so "reached the app" and
# "was blocked at the edge" are told apart by the body, not just the status code.
docker run -d --name "${UPSTREAM}" --network "${NETWORK}" --network-alias app "${IMAGE}" \
  caddy respond --listen :8080 --status 200 --body upstream-ok >/dev/null

# An "http://" site address switches automatic HTTPS off, so the check needs no certificate and no
# DNS; the matcher logic under test is identical either way.
docker run -d --name "${PROXY}" --network "${NETWORK}" -p 127.0.0.1::8081 -p 127.0.0.1::8082 \
  -e CLAVARIS_DOMAIN="http://:8081" \
  -v "${WORKDIR}/Caddyfile:/etc/caddy/Caddyfile:ro" "${IMAGE}" >/dev/null

port_of() { docker port "${PROXY}" "$1/tcp" | head -1 | sed 's/.*://'; }
PRIMARY="http://127.0.0.1:$(port_of 8081)"
CUSTOM="http://127.0.0.1:$(port_of 8082)"

for _ in $(seq 1 40); do
  if curl -s -o /dev/null --max-time 1 "${PRIMARY}/"; then break; fi
  sleep 0.25
done
if ! curl -s -o /dev/null --max-time 2 "${PRIMARY}/"; then
  echo "FAIL: the proxy never came up; its log follows" >&2
  docker logs "${PROXY}" >&2
  exit 1
fi

failures=0

# --path-as-is so curl sends the path byte for byte instead of tidying "/./" and "//" itself.
check() {
  local site="$1" base="$2" host="$3" expectation="$4" path="$5" status body
  status="$(curl -s --path-as-is -H "Host: ${host}" -o "${BODY_FILE}" -w '%{http_code}' "${base}${path}")"
  body="$(cat "${BODY_FILE}")"
  if [ "${expectation}" = "blocked" ]; then
    if [ "${status}" = "404" ] && [ "${body}" != "upstream-ok" ]; then
      echo "ok    ${site} blocked  ${path}"
    else
      echo "FAIL  ${site} ${path} reached the application (status ${status}, body '${body}')" >&2
      failures=$((failures + 1))
    fi
  else
    if [ "${status}" = "200" ] && [ "${body}" = "upstream-ok" ]; then
      echo "ok    ${site} proxied  ${path}"
    else
      echo "FAIL  ${site} ${path} was not proxied (status ${status}, body '${body}')" >&2
      failures=$((failures + 1))
    fi
  fi
}

# What the actuator exposes in production, and the plain ways around a literal-prefix match.
BLOCKED_PATHS=(
  "/actuator"
  "/actuator/"
  "/actuator/health"
  "/actuator/health/readiness"
  "/actuator/info"
  "/actuator/prometheus"
  "/actuator/prometheus?x=1"
  "/Actuator/prometheus"
  "/ACTUATOR/PROMETHEUS"
  "//actuator/prometheus"
  "/./actuator/prometheus"
  "/x/../actuator/prometheus"
  "/actuator;x=1/prometheus"
  "/actuator/prometheus;x=1"
  "/o/x/..;/actuator/prometheus"
  "/%61ctuator/prometheus"
  "/actuato%72/prometheus"
  "/%41ctuator/prometheus"
)

# Everything the application really serves must keep flowing.
PROXIED_PATHS=(
  "/"
  "/o/00000000-0000-0000-0000-000000000000/login"
  "/oauth2/jwks"
  "/.well-known/openid-configuration"
  "/platform/dashboard"
  "/api/v1/admin/organizations"
)

for p in "${BLOCKED_PATHS[@]}"; do
  check "primary" "${PRIMARY}" "localhost" blocked "${p}"
  check "custom " "${CUSTOM}" "custom.example" blocked "${p}"
done
for p in "${PROXIED_PATHS[@]}"; do
  check "primary" "${PRIMARY}" "localhost" proxied "${p}"
  check "custom " "${CUSTOM}" "custom.example" proxied "${p}"
done

if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed" >&2
  exit 1
fi
echo "Caddyfile actuator block verified (primary and custom-domain sites)."
