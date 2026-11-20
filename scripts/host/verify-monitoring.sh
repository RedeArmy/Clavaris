#!/usr/bin/env bash
# TD-PROC-032: does the monitoring stack on THIS host actually work, end to end?
#
# The repository delivers the stack (docker-compose.monitoring.yml, the alert rules, the Alertmanager
# template) and deploy.sh starts it and checks that Prometheus sees the application. What no script
# could say until now is whether the whole chain a person depends on holds on the host that is
# deployed: the configuration is real and not a template, the heartbeat that notices the host dying is
# wired, every target is scraped, every rule loaded and healthy, the always-firing Watchdog arrives
# where it is routed, and a real alert becomes a mail. This runs those checks and prints a verdict per
# line, so the row can be closed on evidence the operator can paste, not on "I think I set it up".
#
# Read-only by default. With --send-test-alert it also raises one alert (severity=critical, so it is
# routed to email at once), which sends ONE real mail to the on-call address and proves Alertmanager's
# SMTP accepted it. Whether the mail reaches the inbox, and the uptime check from outside the host, are
# the two things a script on the host cannot see; they are listed at the end as manual.
#
# Usage (from the deployment directory, as the deploy user):
#   ./verify-monitoring.sh [--send-test-alert]
# Exit code: 0 no automatic check failed; 1 at least one failed.
#
# Overridable: VERIFY_TIMEOUT_SECONDS (60) and VERIFY_POLL_INTERVAL_SECONDS (3) bound each wait;
# VERIFY_MONITORING_COMPOSE is a space-separated list of compose files (tests and the live rig only).

set -uo pipefail

SEND_TEST_ALERT=false
case "${1:-}" in
  --send-test-alert) SEND_TEST_ALERT=true ;;
  "") ;;
  *) echo "Usage: $0 [--send-test-alert]" >&2; exit 64 ;;
esac

TIMEOUT="${VERIFY_TIMEOUT_SECONDS:-60}"
POLL="${VERIFY_POLL_INTERVAL_SECONDS:-3}"
ALERTMANAGER_CONFIG="monitoring/alertmanager.yml"
HEARTBEAT_FILE="monitoring/heartbeat-url"
RULES_FILE="monitoring/alert-rules.yml"
MONITORING_SERVICES=(prometheus alertmanager redis-exporter redis-counters-exporter)

if [ -n "${VERIFY_MONITORING_COMPOSE:-}" ]; then
  read -r -a COMPOSE_FILES <<< "${VERIFY_MONITORING_COMPOSE}"
else
  COMPOSE_FILES=(docker-compose.prod.yml)
  [ -f docker-compose.preprod.yml ] && COMPOSE_FILES+=(docker-compose.preprod.yml)
  COMPOSE_FILES+=(docker-compose.monitoring.yml)
fi
COMPOSE_ARGS=()
for file in "${COMPOSE_FILES[@]}"; do COMPOSE_ARGS+=(-f "${file}"); done

passed=0
failed=0
warned=0
manual=()
pass() { printf '  \033[1;32mPASS\033[0m  %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  \033[1;31mFAIL\033[0m  %s\n' "$1"; failed=$((failed + 1)); }
warn() { printf '  \033[1;33mWARN\033[0m  %s\n' "$1"; warned=$((warned + 1)); }
todo() { manual+=("$1"); }
section() { printf '\n%s\n' "$1"; }

dc() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }
prom_get() { dc exec -T prometheus wget -q -O - "http://localhost:9090$1" 2>/dev/null; }
am_metrics() { dc exec -T alertmanager wget -q -O - "http://localhost:9093/metrics" 2>/dev/null; }

# PromQL goes in a URL; wget will not encode it for us.
encode() { printf '%s' "$1" | sed 's/%/%25/g; s/ /%20/g; s/{/%7B/g; s/}/%7D/g; s/"/%22/g; s/=/%3D/g'; }
prom_query() { prom_get "/api/v1/query?query=$(encode "$1")"; }

# Waits up to TIMEOUT for a command to succeed; the stack may have started a few seconds ago.
wait_for() {
  local waited=0
  while ! "$@" >/dev/null 2>&1; do
    [ "${waited}" -ge "${TIMEOUT}" ] && return 1
    sleep "${POLL}"
    waited=$((waited + POLL))
  done
}

# Sum of a metric across all label sets that mention integration="<name>" (newer Alertmanagers add a
# `reason` label to the failure counter).
am_counter() {
  am_metrics | awk -v metric="$1" -v integration="$2" \
    '$0 ~ "^" metric "\\{" && index($0, "integration=\"" integration "\"") { total += $NF } END { printf "%d", total + 0 }'
}

# Conditions the checks wait for, as plain functions so wait_for can call them.
app_target_up() { prom_query 'up{job="clavaris-app"}' | grep -q '"value":\[[0-9.]*,"1"\]'; }
watchdog_firing() { prom_query 'ALERTS{alertname="Watchdog",alertstate="firing"}' | grep -q '"value":\[[0-9.]*,"1"\]'; }
heartbeat_sent() { [ "$(am_counter alertmanager_notifications_total webhook)" -gt 0 ]; }
# Counts for the rule check: how many alerting rules Prometheus has, and how many are not evaluating yet.
rules_loaded() { prom_get /api/v1/rules | grep -o '"type":"alerting"' | wc -l; }
rules_unhealthy() { prom_get /api/v1/rules | grep -o '"health":"\(err\|unknown\)"' | wc -l; }
rules_all_loaded_and_healthy() {
  [ "${expected_rules}" -gt 0 ] && [ "$(rules_loaded)" -eq "${expected_rules}" ] && [ "$(rules_unhealthy)" -eq 0 ]
}
email_sent_beyond() { [ "$(am_counter alertmanager_notifications_total email)" -gt "$1" ]; }

echo "Monitoring acceptance check (TD-PROC-032) in $(pwd)"

section "1. The configuration is the operator's, not the template"
if [ ! -f "${ALERTMANAGER_CONFIG}" ]; then
  fail "${ALERTMANAGER_CONFIG} does not exist: nothing starts the monitoring stack on this host. Create it from monitoring/alertmanager.prod.yml.example (deployment-runbook.md section 7e)."
  printf '\n%s passed, %s failed. Nothing else can be checked until that file exists.\n' "${passed}" "${failed}"
  exit 1
fi
# Comment lines do not count: the template's header names the placeholders, and deploy.sh ignores it too.
if grep -v '^[[:space:]]*#' "${ALERTMANAGER_CONFIG}" | grep -q 'REPLACE_WITH_'; then
  fail "${ALERTMANAGER_CONFIG} still has a REPLACE_WITH_ placeholder: $(grep -v '^[[:space:]]*#' "${ALERTMANAGER_CONFIG}" | grep -c 'REPLACE_WITH_') left. No alert would reach anyone."
else
  pass "${ALERTMANAGER_CONFIG} has no placeholder left"
fi
heartbeat_routed=false
if grep -q 'heartbeat-url' "${ALERTMANAGER_CONFIG}"; then
  heartbeat_routed=true
  if [ -s "${HEARTBEAT_FILE}" ] && grep -Eq '^https?://[^[:space:]]+$' "${HEARTBEAT_FILE}"; then
    pass "${HEARTBEAT_FILE} holds a URL (the Watchdog is routed to it, so a dead host is noticed)"
  else
    fail "${HEARTBEAT_FILE} is missing, empty or not a URL, but the Alertmanager config routes the Watchdog to it: the host dying would not be noticed (runbook section 7e, 'The host disappearing')."
  fi
else
  warn "the Alertmanager config has no heartbeat route: the host dying is not detected. If that is on purpose, it must be recorded in risk-register.md."
fi

section "2. The stack is running"
running="$(dc ps --services --status running 2>/dev/null || true)"
for service in "${MONITORING_SERVICES[@]}"; do
  if grep -qx "${service}" <<< "${running}"; then
    pass "${service} is running"
  else
    fail "${service} is not running (docker compose ps; run ./deploy.sh or: docker compose ${COMPOSE_ARGS[*]} up -d ${service})"
  fi
done

section "3. Prometheus sees everything it should"
if wait_for app_target_up; then
  pass "the application target is up (clavaris-app)"
else
  fail "Prometheus does not report the application target up within ${TIMEOUT}s (is the app healthy? does app:8080/actuator/prometheus answer?)"
fi
redis_up="$(prom_query 'up{job="redis"}')"
redis_ok="$(grep -o '"value":\[[0-9.]*,"1"\]' <<< "${redis_up}" | wc -l)"
redis_down="$(grep -o '"value":\[[0-9.]*,"0"\]' <<< "${redis_up}" | wc -l)"
if [ "${redis_ok}" -eq 2 ] && [ "${redis_down}" -eq 0 ]; then
  pass "both Redis exporters are up (sessions and counters)"
else
  fail "expected both Redis targets up, found ${redis_ok} up and ${redis_down} down (the exporters or the Redis instances)"
fi
if grep -q '"activeAlertmanagers":\[{' <<< "$(prom_get /api/v1/alertmanagers)"; then
  pass "Prometheus has found an active Alertmanager"
else
  fail "Prometheus reports no active Alertmanager: alerts would be evaluated and sent nowhere"
fi

section "4. Every alert rule is loaded and healthy"
expected_rules=0
[ -f "${RULES_FILE}" ] && expected_rules="$(grep -c '^ *- alert:' "${RULES_FILE}" || true)"
# A rule is "unknown" until Prometheus has evaluated it once, up to one interval after the stack starts.
if [ "${expected_rules}" -eq 0 ]; then
  fail "${RULES_FILE} not found or has no rules: cannot say how many should be loaded"
elif wait_for rules_all_loaded_and_healthy; then
  pass "all $(rules_loaded) alert rules are loaded and evaluating"
else
  fail "$(rules_loaded) of ${expected_rules} alert rules are loaded and $(rules_unhealthy) are not healthy after ${TIMEOUT}s (docker compose logs prometheus shows why a rule file was rejected)"
fi

section "5. The Watchdog, and the host-is-alive heartbeat"
if wait_for watchdog_firing; then
  pass "the Watchdog is firing (the rules are evaluated and alerts do fire)"
else
  fail "the Watchdog is not firing within ${TIMEOUT}s: the rule file is not the one Prometheus reads, or evaluation is stuck"
fi
if [ "${heartbeat_routed}" = true ]; then
  if wait_for heartbeat_sent; then
    sent="$(am_counter alertmanager_notifications_total webhook)"
    failed_count="$(am_counter alertmanager_notifications_failed_total webhook)"
    if [ "${failed_count}" -eq 0 ]; then
      pass "the heartbeat was delivered ${sent} time(s), none failed"
    else
      fail "the heartbeat: ${sent} sent, ${failed_count} failed. The URL in ${HEARTBEAT_FILE} is wrong or the service rejects it."
    fi
  else
    fail "no heartbeat was sent within ${TIMEOUT}s although the Watchdog is routed to it"
  fi
fi
todo "Open the heartbeat service (healthchecks.io, Better Stack, Cronitor) and see that the check shows a ping from this host."

section "6. An alert becomes a mail"
email_to="$(sed -n 's/^[[:space:]]*-\{0,1\}[[:space:]]*to:[[:space:]]*"\{0,1\}\([^"[:space:]]*\)"\{0,1\}.*/\1/p' "${ALERTMANAGER_CONFIG}" | head -n 1)"
if [ "${SEND_TEST_ALERT}" = true ]; then
  before="$(am_counter alertmanager_notifications_total email)"
  failed_before="$(am_counter alertmanager_notifications_failed_total email)"
  if dc exec -T alertmanager amtool alert add MonitoringAcceptanceTest severity=critical instance=acceptance-check \
       --annotation=summary="monitoring acceptance test: a mail means the chain works" \
       --alertmanager.url=http://localhost:9093 >/dev/null 2>&1; then
    if wait_for email_sent_beyond "${before}"; then
      after_failed="$(am_counter alertmanager_notifications_failed_total email)"
      if [ "${after_failed}" -gt "${failed_before}" ]; then
        fail "Alertmanager tried to send the test mail and the send failed (the SMTP host, the credential or the sending domain: docker compose logs alertmanager)"
      else
        pass "Alertmanager sent the test mail and its SMTP server accepted it"
      fi
    else
      fail "no mail was attempted within ${TIMEOUT}s after the test alert was raised (the critical route or the receiver is not what the template says)"
    fi
  else
    fail "could not raise the test alert with amtool inside the alertmanager container"
  fi
  todo "Check the inbox of ${email_to:-the on-call address}: one mail titled MonitoringAcceptanceTest must have arrived. SMTP accepting it is not the same as the inbox getting it (spam, an unverified domain)."
else
  todo "Run this again with --send-test-alert. It raises one alert and sends ONE real mail to ${email_to:-the on-call address}; that is the only way to prove the last link."
fi

section "7. From outside the host"
base_url="$(sed -n "s/^[[:space:]]*CLAVARIS_BASE_URL=[\"']*\([^\"'[:space:]]*\).*/\1/p" .env 2>/dev/null | tail -n 1)"
todo "Add an uptime check at the heartbeat service on an Organization's discovery document, which crosses Caddy, the application and the database: ${base_url:-https://<host>}/o/<organizationId>/.well-known/openid-configuration (the actuator endpoints are blocked at the edge by design)."

printf '\n%s passed, %s failed, %s warning(s).\n' "${passed}" "${failed}" "${warned}"
if [ "${#manual[@]}" -gt 0 ]; then
  printf '\nStill for a person (a script on this host cannot see them):\n'
  for item in "${manual[@]}"; do printf '  [ ] %s\n' "${item}"; done
fi
if [ "${failed}" -eq 0 ]; then
  printf '\nEvery automatic check passed. TD-PROC-032 closes when the items above are done (technical-debt-register.md).\n'
  exit 0
fi
exit 1
