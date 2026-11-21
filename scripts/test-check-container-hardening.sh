#!/usr/bin/env bash
# Tests for check-container-hardening.sh (TD-TEST-022): a resolved configuration that has everything passes,
# and each property, removed one at a time, fails with a message that names it. No docker: the script is
# given the resolved configuration as JSON.
#
# Usage: ./scripts/test-check-container-hardening.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${HERE}/check-container-hardening.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
PYTHON="$(command -v python3 || command -v python)"

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

# A resolved prod configuration and a monitoring one that satisfy every rule.
"${PYTHON}" - "${WORK}" <<'PY'
import json, sys
w = sys.argv[1]
hard = {"cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"], "pids_limit": 100,
        "read_only": True, "mem_limit": "1000000"}
prod = {"services": {
    "postgres": {**hard, "cap_add": ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"],
                 "environment": {"POSTGRES_USER": "boot"}},
    "redis": {**hard, "cap_add": ["CHOWN", "SETGID", "SETPCAP", "SETUID"]},
    "redis-counters": {**hard, "cap_add": ["CHOWN", "SETGID", "SETPCAP", "SETUID"]},
    "caddy": {**hard, "cap_add": ["NET_BIND_SERVICE"]},
    "app": {**hard, "environment": {"DB_DEV_USER": "clavaris_app", "DB_DEV_PASSWORD": "a",
                                    "SPRING_FLYWAY_USER": "clavaris_owner", "SPRING_FLYWAY_PASSWORD": "b"}},
}}
mon = {"services": {
    "prometheus": {"cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"], "pids_limit": 5},
    "redis-exporter": {"cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"], "pids_limit": 5, "read_only": True},
}}
json.dump(prod, open(w + "/prod.json", "w"))
json.dump(mon, open(w + "/mon.json", "w"))
PY

# mutate <expression over p (prod) and m (monitoring)>: writes prod-m.json and mon-m.json.
mutate() {
  "${PYTHON}" - "${WORK}" "$1" <<'PY'
import json, sys
w, expr = sys.argv[1], sys.argv[2]
p = json.load(open(w + "/prod.json")); m = json.load(open(w + "/mon.json"))
exec(expr)
json.dump(p, open(w + "/prod-m.json", "w")); json.dump(m, open(w + "/mon-m.json", "w"))
PY
}

# expect_fail <description> <text the output must contain> <mutation>
expect_fail() {
  mutate "$3"
  local out
  out="$(bash "${CHECK}" --json "${WORK}/prod-m.json" "${WORK}/mon-m.json" 2>&1)"
  local status=$?
  if [ "${status}" -eq 1 ] && grep -qF -- "$2" <<< "${out}"; then pass "$1"; else fail "$1 (status ${status}: ${out})"; fi
}

echo "check-container-hardening.sh"

out="$(bash "${CHECK}" --json "${WORK}/prod.json" "${WORK}/mon.json" 2>&1)"; status=$?
[ "${status}" -eq 0 ] && pass "a fully hardened configuration passes" || fail "a good configuration failed: ${out}"

expect_fail "a service without cap_drop ALL fails" "app: cap_drop does not contain ALL" \
  "del p['services']['app']['cap_drop']"
expect_fail "an extra capability fails" "caddy: cap_add has ['NET_ADMIN']" \
  "p['services']['caddy']['cap_add'].append('NET_ADMIN')"
expect_fail "a capability a service has no business with fails" "redis: cap_add has ['DAC_OVERRIDE']" \
  "p['services']['redis']['cap_add'].append('DAC_OVERRIDE')"
expect_fail "no-new-privileges missing fails" "postgres: security_opt does not contain no-new-privileges" \
  "p['services']['postgres']['security_opt'] = []"
expect_fail "a missing process limit fails" "redis-counters: pids_limit is not set" \
  "del p['services']['redis-counters']['pids_limit']"
expect_fail "a writable root fails" "app: read_only is not true" \
  "p['services']['app']['read_only'] = False"
expect_fail "a missing memory limit fails" "postgres: mem_limit is not set" \
  "del p['services']['postgres']['mem_limit']"
expect_fail "a privileged container fails" "caddy: privileged" \
  "p['services']['caddy']['privileged'] = True"
expect_fail "host networking fails" "app: host networking" \
  "p['services']['app']['network_mode'] = 'host'"
expect_fail "the Docker socket fails" "mounts the Docker socket" \
  "p['services']['caddy']['volumes'] = [{'source': '/var/run/docker.sock', 'target': '/s'}]"
expect_fail "a new service nobody hardened fails" "newservice: cap_drop does not contain ALL" \
  "p['services']['newservice'] = {'pids_limit': 5, 'read_only': True, 'mem_limit': '1', 'security_opt': ['no-new-privileges:true']}"
expect_fail "a monitoring service that lost cap_drop fails" "docker-compose.monitoring.yml: redis-exporter: cap_drop" \
  "m['services']['redis-exporter']['cap_drop'] = []"
expect_fail "an exporter with a writable root fails" "redis-exporter: read_only is not true" \
  "m['services']['redis-exporter']['read_only'] = False"

# TD-SEC-106
expect_fail "the application connecting as the superuser fails" "app connects as 'boot', not clavaris_app" \
  "p['services']['app']['environment']['DB_DEV_USER'] = 'boot'"
expect_fail "Flyway running as the superuser fails" "Flyway runs as 'boot', not clavaris_owner" \
  "p['services']['app']['environment']['SPRING_FLYWAY_USER'] = 'boot'"
expect_fail "one password for both roles fails" "share a password" \
  "p['services']['app']['environment']['SPRING_FLYWAY_PASSWORD'] = 'a'"

echo
if ((failures > 0)); then
  printf '%d check(s) failed\n' "${failures}"
  exit 1
fi
echo "all checks passed"
