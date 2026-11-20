#!/usr/bin/env bash
# Tests for verify-monitoring.sh (TD-PROC-032) with a stand-in "docker" that answers like Prometheus and
# Alertmanager do, so every branch runs in seconds and in CI. The script is also proven against the real
# stack by test-verify-monitoring-live.sh, which needs Docker images and is run by hand.
#
# Usage: ./scripts/host/test-verify-monitoring.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="${HERE}/verify-monitoring.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "${STUB_LOG}"
args="$*"
vector() { printf '{"status":"success","data":{"resultType":"vector","result":[%s]}}' "$1"; }
sample() { printf '{"metric":{},"value":[1700000000.1,"%s"]}' "$1"; }
case "${args}" in
  *"ps --services"*)
    for s in prometheus alertmanager redis-exporter redis-counters-exporter; do
      [ "${s}" = "${STUB_DOWN_SERVICE:-}" ] || echo "${s}"
    done ;;
  *"amtool alert add"*)
    [ "${STUB_AMTOOL_FAILS:-}" = 1 ] && exit 1
    n=$(cat "${STUB_STATE}/email" 2>/dev/null || echo 0); echo $((n + 1)) > "${STUB_STATE}/email"
    [ "${STUB_EMAIL_FAILS:-}" = 1 ] && echo 1 > "${STUB_STATE}/email_failed" ;;
  *"exec -T alertmanager wget"*)
    email=$(cat "${STUB_STATE}/email" 2>/dev/null || echo 0)
    email_failed=$(cat "${STUB_STATE}/email_failed" 2>/dev/null || echo 0)
    printf 'alertmanager_notifications_total{integration="email"} %s\n' "${email}"
    printf 'alertmanager_notifications_failed_total{integration="email",reason="other"} %s\n' "${email_failed}"
    printf 'alertmanager_notifications_total{integration="webhook"} %s\n' "${STUB_HEARTBEATS-3}"
    printf 'alertmanager_notifications_failed_total{integration="webhook",reason="other"} %s\n' "${STUB_HEARTBEAT_FAILED-0}" ;;
  *"exec -T prometheus wget"*)
    case "${args}" in
      *alertmanagers*)
        [ "${STUB_NO_ALERTMANAGER:-}" = 1 ] \
          && echo '{"status":"success","data":{"activeAlertmanagers":[],"droppedAlertmanagers":[]}}' \
          || echo '{"status":"success","data":{"activeAlertmanagers":[{"url":"http://alertmanager:9093/api/v2/alerts"}]}}' ;;
      */api/v1/rules*)
        n="${STUB_RULES_LOADED-3}"; health="${STUB_RULE_HEALTH:-ok}"
        printf '{"status":"success","data":{"groups":[{"rules":['
        for ((i = 0; i < n; i++)); do [ "${i}" -gt 0 ] && printf ','; printf '{"type":"alerting","health":"%s"}' "${health}"; done
        printf ']}]}}\n' ;;
      *clavaris-app*) vector "$(sample "${STUB_APP_UP-1}")" ;;
      *Watchdog*) [ "${STUB_NO_WATCHDOG:-}" = 1 ] && vector "" || vector "$(sample 1)" ;;
      *%22redis%22*) vector "$(sample "${STUB_REDIS_A-1}"),$(sample "${STUB_REDIS_B-1}")" ;;
    esac ;;
esac
exit 0
STUB
chmod +x "${WORK}/bin/docker"

# run [flags] with the scenario in the environment; leaves RC and OUT.
run() {
  local dir="${WORK}/host"
  rm -rf "${dir}" "${WORK}/state"; mkdir -p "${dir}/monitoring" "${WORK}/state"
  : > "${dir}/docker-compose.prod.yml"
  printf 'global:\n  smtp_from: ops@example.org\nroutes:\n  - heartbeat: /etc/alertmanager/heartbeat-url\nreceivers:\n  - to: "oncall@example.org"\n' > "${dir}/monitoring/alertmanager.yml"
  printf 'https://hc-ping.example/abc\n' > "${dir}/monitoring/heartbeat-url"
  printf 'groups:\n  - rules:\n    - alert: A\n    - alert: B\n    - alert: Watchdog\n' > "${dir}/monitoring/alert-rules.yml"
  printf 'CLAVARIS_BASE_URL=https://id.example.org\n' > "${dir}/.env"
  [ -n "${PRE:-}" ] && eval "${PRE}"
  OUT="${WORK}/out.log"
  (
    cd "${dir}" || exit 99
    PATH="${WORK}/bin:${PATH}" STUB_LOG="${WORK}/calls.log" STUB_STATE="${WORK}/state" \
      VERIFY_TIMEOUT_SECONDS=1 VERIFY_POLL_INTERVAL_SECONDS=1 "${VERIFY}" "$@" > "${OUT}" 2>&1
  )
  RC=$?
}

echo "everything in place, read-only"
: > "${WORK}/calls.log"
run
[ "${RC}" -eq 0 ] && pass "exit 0" || { fail "exit code ${RC}"; cat "${OUT}"; }
grep -q "all 3 alert rules are loaded" "${OUT}" && pass "counts the rules from the file against Prometheus" || fail "rule count"
grep -q "the heartbeat was delivered 3 time" "${OUT}" && pass "sees the heartbeat" || fail "heartbeat"
grep -q "amtool" "${WORK}/calls.log" && fail "raised an alert without --send-test-alert" || pass "raises no alert without the flag"
grep -q "Run this again with --send-test-alert" "${OUT}" && pass "tells the operator the last link is untested" || fail "no prompt"
grep -q "https://id.example.org/o/<organizationId>/.well-known/openid-configuration" "${OUT}" && pass "names the discovery URL for the outside check" || fail "no URL for the uptime check"

echo "with --send-test-alert"
run --send-test-alert
[ "${RC}" -eq 0 ] && pass "exit 0" || { fail "exit code ${RC}"; cat "${OUT}"; }
grep -q "SMTP server accepted it" "${OUT}" && pass "sees the mail attempted and not failed" || fail "mail not seen"
grep -q "Check the inbox of oncall@example.org" "${OUT}" && pass "names the inbox to look at" || fail "address not shown"
STUB_EMAIL_FAILS=1 run --send-test-alert
[ "${RC}" -eq 1 ] && grep -q "the send failed" "${OUT}" && pass "a failed send fails the check" || fail "failed send not caught (exit ${RC})"
STUB_AMTOOL_FAILS=1 run --send-test-alert
[ "${RC}" -eq 1 ] && grep -q "could not raise the test alert" "${OUT}" && pass "an alert that cannot be raised fails" || fail "amtool failure not caught"

echo "configuration"
PRE='rm -f "${dir}/monitoring/alertmanager.yml"' run
[ "${RC}" -eq 1 ] && grep -q "does not exist" "${OUT}" && pass "no Alertmanager file: fails and stops" || fail "missing file not caught"
PRE='printf "smtp_auth_password: REPLACE_WITH_A_REAL_RESEND_API_KEY\n" >> "${dir}/monitoring/alertmanager.yml"' run
[ "${RC}" -eq 1 ] && grep -q "still has a REPLACE_WITH_ placeholder" "${OUT}" && pass "a placeholder fails" || fail "placeholder not caught"
PRE='printf "# replace every REPLACE_WITH_ value\n" >> "${dir}/monitoring/alertmanager.yml"' run
[ "${RC}" -eq 0 ] && grep -q "no placeholder left" "${OUT}" && pass "a comment that names it does not" || fail "comment counted as a placeholder"
PRE='rm -f "${dir}/monitoring/heartbeat-url"' run
[ "${RC}" -eq 1 ] && grep -q "is missing, empty or not a URL" "${OUT}" && pass "a routed heartbeat with no URL fails" || fail "missing heartbeat file not caught"
PRE='printf "global:\n  smtp_from: ops@example.org\nreceivers:\n  - to: \"oncall@example.org\"\n" > "${dir}/monitoring/alertmanager.yml"; rm -f "${dir}/monitoring/heartbeat-url"' run
[ "${RC}" -eq 0 ] && grep -q "no heartbeat route" "${OUT}" && pass "no heartbeat route at all is a warning, not a failure" || fail "unrouted heartbeat handled wrongly (exit ${RC})"

echo "the stack"
STUB_DOWN_SERVICE=redis-exporter run
[ "${RC}" -eq 1 ] && grep -q "redis-exporter is not running" "${OUT}" && pass "a service that is not running fails, by name" || fail "stopped service not caught"
STUB_APP_UP=0 run
[ "${RC}" -eq 1 ] && grep -q "does not report the application target up" "${OUT}" && pass "an application target that is down fails" || fail "app target down not caught"
STUB_REDIS_B=0 run
[ "${RC}" -eq 1 ] && grep -q "found 1 up and 1 down" "${OUT}" && pass "one Redis target down fails" || fail "redis target down not caught"
STUB_NO_ALERTMANAGER=1 run
[ "${RC}" -eq 1 ] && grep -q "no active Alertmanager" "${OUT}" && pass "no Alertmanager found fails" || fail "missing alertmanager not caught"
STUB_RULES_LOADED=2 run
[ "${RC}" -eq 1 ] && grep -q "2 of 3 alert rules are loaded" "${OUT}" && pass "a rule that did not load fails" || fail "missing rule not caught"
STUB_RULE_HEALTH=err run
[ "${RC}" -eq 1 ] && grep -q "3 are not healthy" "${OUT}" && pass "rules that error fail" || fail "unhealthy rules not caught"
STUB_NO_WATCHDOG=1 run
[ "${RC}" -eq 1 ] && grep -q "the Watchdog is not firing" "${OUT}" && pass "no Watchdog fails" || fail "missing watchdog not caught"
STUB_HEARTBEATS=0 run
[ "${RC}" -eq 1 ] && grep -q "no heartbeat was sent" "${OUT}" && pass "a heartbeat that never leaves fails" || fail "silent heartbeat not caught"
STUB_HEARTBEAT_FAILED=2 run
[ "${RC}" -eq 1 ] && grep -q "2 failed" "${OUT}" && pass "a heartbeat that is rejected fails" || fail "rejected heartbeat not caught"

echo "an unknown option"
run --nonsense
[ "${RC}" -eq 64 ] && pass "is refused with the usage" || fail "exit code ${RC}, expected 64"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All verify-monitoring.sh checks passed."
