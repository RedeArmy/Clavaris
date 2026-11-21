#!/usr/bin/env bash
# Fails when the compose files lose the hardening TD-SEC-105 and TD-SEC-106 gave them (TD-TEST-022).
#
# What it checks, per service, on the resolved configuration (`docker compose config`, so an anchor, an
# override file or a variable default cannot hide a missing line):
#   - cap_drop contains ALL, and cap_add holds only what that service's image needs to start;
#   - security_opt contains no-new-privileges:true;
#   - pids_limit is set;
#   - read_only is true (docker-compose.prod.yml services; the monitoring file's two exporters);
#   - mem_limit is set (docker-compose.prod.yml services);
#   - neither privileged nor host networking nor the Docker socket is used.
# And, for the application service, that it does not connect to Postgres as the superuser the image creates
# (DB_DEV_USER is clavaris_app, Flyway's user is clavaris_owner, and neither is POSTGRES_USER).
#
# Usage: ./scripts/check-container-hardening.sh                       (resolves the files with docker compose)
#        ./scripts/check-container-hardening.sh --json prod.json [monitoring.json]   (tests: no docker)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/.." && pwd)"
PYTHON="$(command -v python3 || command -v python)"

PROD_JSON=""
MON_JSON=""
if [ "${1:-}" = "--json" ]; then
  PROD_JSON="${2:?--json needs the resolved docker-compose.prod.yml as JSON}"
  MON_JSON="${3:-}"
else
  command -v docker >/dev/null || { echo "check-container-hardening: docker is needed to resolve the compose files" >&2; exit 2; }
  WORK="$(mktemp -d)"
  trap 'rm -rf "${WORK}"' EXIT
  # The compose file refuses to resolve without the variables it marks as required; a placeholder for every
  # name in .env.example is enough, and the limits that must be numbers are left to their defaults.
  grep -oE '^[A-Z][A-Z0-9_]*=' "${ROOT}/.env.example" \
    | grep -vE 'MEM_LIMIT|MAXMEMORY|PIDS|PORT|TIMEOUT|RETENTION' \
    | sed -E 's/^([A-Z0-9_]+)=$/\1=placeholder-\1-0123456789/' > "${WORK}/env"
  docker compose --env-file "${WORK}/env" -f "${ROOT}/docker-compose.prod.yml" config --format json > "${WORK}/prod.json"
  docker compose --env-file "${WORK}/env" -f "${ROOT}/docker-compose.monitoring.yml" config --format json > "${WORK}/mon.json"
  PROD_JSON="${WORK}/prod.json"
  MON_JSON="${WORK}/mon.json"
fi

"${PYTHON}" - "${PROD_JSON}" "${MON_JSON}" <<'PY'
import json
import sys

# What each service may add back after cap_drop: ALL. Anything else is a new capability and needs a reason.
ALLOWED_CAP_ADD = {
    "postgres": {"CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"},
    "redis": {"CHOWN", "SETGID", "SETPCAP", "SETUID"},
    "redis-counters": {"CHOWN", "SETGID", "SETPCAP", "SETUID"},
    "caddy": {"NET_BIND_SERVICE"},
}
# Monitoring services that keep a writable root (they write their own data under it).
MONITORING_WRITABLE = {"prometheus", "alertmanager"}

problems = []


def check(file_label, services, expect_read_only, expect_mem_limit, read_only_exempt=()):
    for name, svc in sorted(services.items()):
        where = f"{file_label}: {name}"
        if "ALL" not in (svc.get("cap_drop") or []):
            problems.append(f"{where}: cap_drop does not contain ALL")
        extra = set(svc.get("cap_add") or []) - ALLOWED_CAP_ADD.get(name, set())
        if extra:
            problems.append(f"{where}: cap_add has {sorted(extra)}, which this service is not allowed to add back")
        if "no-new-privileges:true" not in (svc.get("security_opt") or []):
            problems.append(f"{where}: security_opt does not contain no-new-privileges:true")
        if not svc.get("pids_limit"):
            problems.append(f"{where}: pids_limit is not set")
        if expect_read_only and name not in read_only_exempt and svc.get("read_only") is not True:
            problems.append(f"{where}: read_only is not true")
        if expect_mem_limit and not svc.get("mem_limit"):
            problems.append(f"{where}: mem_limit is not set")
        if svc.get("privileged"):
            problems.append(f"{where}: privileged")
        if svc.get("network_mode") == "host":
            problems.append(f"{where}: host networking")
        for volume in svc.get("volumes") or []:
            if "docker.sock" in str(volume.get("source", "")):
                problems.append(f"{where}: mounts the Docker socket")


with open(sys.argv[1], encoding="utf-8") as handle:
    prod = json.load(handle)["services"]
check("docker-compose.prod.yml", prod, expect_read_only=True, expect_mem_limit=True)

if sys.argv[2]:
    with open(sys.argv[2], encoding="utf-8") as handle:
        monitoring = json.load(handle)["services"]
    check("docker-compose.monitoring.yml", monitoring, expect_read_only=True, expect_mem_limit=False,
          read_only_exempt=MONITORING_WRITABLE)

# TD-SEC-106: the application is not the superuser.
app_env = prod.get("app", {}).get("environment", {})
superuser = prod.get("postgres", {}).get("environment", {}).get("POSTGRES_USER")
if app_env.get("DB_DEV_USER") != "clavaris_app":
    problems.append(f"docker-compose.prod.yml: app connects as {app_env.get('DB_DEV_USER')!r}, not clavaris_app")
if app_env.get("SPRING_FLYWAY_USER") != "clavaris_owner":
    problems.append(f"docker-compose.prod.yml: Flyway runs as {app_env.get('SPRING_FLYWAY_USER')!r}, not clavaris_owner")
if superuser and superuser in (app_env.get("DB_DEV_USER"), app_env.get("SPRING_FLYWAY_USER")):
    problems.append("docker-compose.prod.yml: the application or Flyway uses the postgres superuser")
if app_env.get("DB_DEV_PASSWORD") and app_env.get("DB_DEV_PASSWORD") == app_env.get("SPRING_FLYWAY_PASSWORD"):
    problems.append("docker-compose.prod.yml: the runtime and the migration role share a password")

if problems:
    print("Container hardening (TD-SEC-105, TD-SEC-106) was lost:")
    for problem in problems:
        print("  - " + problem)
    sys.exit(1)
print("OK: every service drops its capabilities, forbids gaining privileges and is bounded; the application is not the superuser.")
PY
