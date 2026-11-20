#!/usr/bin/env bash
# TD-PROC-032: runs verify-monitoring.sh against the REAL Prometheus and Alertmanager of
# docker-compose.monitoring.yml (same images, same digests), with the repository's own prometheus.yml,
# alert-rules.yml and the Alertmanager template, so the acceptance script is proven on the thing it
# will judge on the host and not only against canned answers.
#
# What is stood in for: the application (a static /actuator/prometheus), the two Redis instances, the
# SMTP server (Mailpit, whose API says whether a mail arrived) and the heartbeat service (a small
# receiver that counts pings). Everything between them is the production stack.
#
# Needs Docker and pulls five small images, so it is a manual check and is not run by CI:
#   ./scripts/host/test-verify-monitoring-live.sh
# Takes about two minutes.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RIG="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="clavaris-monitoring-rig-$$"
export MONITORING_PROMETHEUS_PORT=19090 MONITORING_ALERTMANAGER_PORT=19093
MAIL_API="http://127.0.0.1:18025"
failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

cleanup() {
  (cd "${RIG}" && docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml down -v --remove-orphans >/dev/null 2>&1)
  rm -rf "${RIG}"
}
trap cleanup EXIT

mkdir -p "${RIG}/monitoring"
cp "${REPO}/docker-compose.monitoring.yml" "${RIG}/"
cp "${REPO}/infra/observability/prometheus.yml" "${RIG}/monitoring/prometheus.yml"
cp "${REPO}/infra/observability/alert-rules.yml" "${RIG}/monitoring/alert-rules.yml"
cat > "${RIG}/app.py" <<'PY'
import http.server, socketserver
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        # The real application answers with the Prometheus text format; Prometheus 3 refuses a body
        # whose Content-Type it does not recognise, so the stand-in has to say it too.
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"# HELP stub a stand-in for the application\n# TYPE stub gauge\nstub 1\n")
    def log_message(self, *args): pass
socketserver.TCPServer(("", 8080), Handler).serve_forever()
PY
printf 'http://receiver:8000/ping\n' > "${RIG}/monitoring/heartbeat-url"

# The production template, with only what a test host cannot have changed: the SMTP server (a sink with
# no TLS and no login) and the two addresses. Routes and receivers are the template's own.
sed \
  -e 's#smtp.resend.com:587#mailpit:1025#' \
  -e 's#clavaris-alerts@REPLACE_WITH_YOUR_VERIFIED_RESEND_DOMAIN#clavaris-alerts@example.test#' \
  -e '/smtp_auth_username/d' -e '/smtp_auth_password/d' \
  -e 's#smtp_require_tls: true#smtp_require_tls: false#' \
  -e 's#REPLACE_WITH_A_REAL_ON_CALL_ADDRESS#oncall@example.test#' \
  "${REPO}/infra/observability/alertmanager.prod.yml.example" > "${RIG}/monitoring/alertmanager.yml"

cat > "${RIG}/receiver.py" <<'PY'
import http.server, socketserver
pings = 0
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        global pings
        pings += 1
        self.send_response(200); self.end_headers()
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(str(pings).encode())
    def log_message(self, *args): pass
socketserver.TCPServer(("", 8000), Handler).serve_forever()
PY

cat > "${RIG}/docker-compose.prod.yml" <<'YML'
services:
  app:
    image: python:3.12-alpine
    command: ["python", "/app.py"]
    volumes: ["./app.py:/app.py:ro"]
  redis:
    image: redis:7-alpine
  redis-counters:
    image: redis:7-alpine
  mailpit:
    image: axllent/mailpit:v1.21
    ports: ["127.0.0.1:18025:8025"]
  receiver:
    image: python:3.12-alpine
    command: ["python", "/receiver.py"]
    volumes: ["./receiver.py:/receiver.py:ro"]
YML

cd "${RIG}" || exit 1
echo "starting the rig (the repository's own monitoring stack plus stand-ins)"
docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml up -d >/dev/null 2>&1 \
  && pass "the stack starts" || { fail "the stack did not start"; docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml logs 2>&1 | tail -20; exit 1; }

export VERIFY_MONITORING_COMPOSE="docker-compose.prod.yml docker-compose.monitoring.yml" VERIFY_TIMEOUT_SECONDS=90
VERIFY="${REPO}/scripts/host/verify-monitoring.sh"

echo
echo "a working stack, read-only"
"${VERIFY}" > "${RIG}/out1.log" 2>&1; rc=$?
cat "${RIG}/out1.log" | sed 's/^/    | /'
[ "${rc}" -eq 0 ] && pass "no automatic check fails" || fail "exit code ${rc}"
grep -q "all [0-9]* alert rules are loaded" "${RIG}/out1.log" && pass "every rule of the repository is loaded and healthy" || fail "rules"
grep -q "the heartbeat was delivered" "${RIG}/out1.log" && pass "the Watchdog's heartbeat arrived" || fail "heartbeat"
grep -q "Run this again with --send-test-alert" "${RIG}/out1.log" && pass "asks for the explicit flag before sending a mail" || fail "no prompt for the flag"
# Other alerts may legitimately mail while the stand-in application has no real metrics; what must not
# happen is the acceptance test's own alert being raised without the flag.
curl -fsS "${MAIL_API}/api/v1/messages" 2>/dev/null | grep -q 'MonitoringAcceptanceTest' \
  && fail "the test alert was raised without --send-test-alert" || pass "raised no test alert without the flag"
[ "$(docker compose -f docker-compose.prod.yml exec -T receiver python -c 'import urllib.request;print(urllib.request.urlopen("http://localhost:8000/").read().decode())' 2>/dev/null)" -ge 1 ] 2>/dev/null \
  && pass "the receiver counted at least one ping" || fail "the receiver saw no ping"

echo
echo "the end-to-end test alert"
"${VERIFY}" --send-test-alert > "${RIG}/out2.log" 2>&1; rc=$?
[ "${rc}" -eq 0 ] && pass "no automatic check fails" || { fail "exit code ${rc}"; sed 's/^/    | /' "${RIG}/out2.log"; }
grep -q "SMTP server accepted it" "${RIG}/out2.log" && pass "Alertmanager reports the mail accepted" || fail "no SMTP acceptance reported"
sleep 2
messages="$(curl -fsS "${MAIL_API}/api/v1/messages" 2>/dev/null)"
grep -q 'MonitoringAcceptanceTest' <<< "${messages}" && pass "the mail really arrived at the sink" || fail "no mail with the test alert's name in the sink: ${messages:0:200}"
grep -q 'oncall@example.test' <<< "${messages}" && pass "addressed to the on-call address of the config" || fail "wrong recipient"
grep -q "oncall@example.test" "${RIG}/out2.log" && pass "the script names that address for the manual inbox check" || fail "address not shown to the operator"

echo
echo "a Redis exporter that is down"
docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml stop redis-counters-exporter >/dev/null 2>&1
VERIFY_TIMEOUT_SECONDS=20 "${VERIFY}" > "${RIG}/out3.log" 2>&1; rc=$?
[ "${rc}" -eq 1 ] && pass "the check fails" || fail "exit code ${rc}, expected 1"
grep -q "redis-counters-exporter is not running" "${RIG}/out3.log" && pass "and names the service" || fail "service not named"
docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml start redis-counters-exporter >/dev/null 2>&1

echo
echo "an Alertmanager configuration with a placeholder left in it"
cp monitoring/alertmanager.yml monitoring/alertmanager.good
printf 'smtp_auth_password: "REPLACE_WITH_A_REAL_RESEND_API_KEY"\n' >> monitoring/alertmanager.yml
"${VERIFY}" > "${RIG}/out4.log" 2>&1; rc=$?
[ "${rc}" -eq 1 ] && grep -q "still has a REPLACE_WITH_ placeholder" "${RIG}/out4.log" && pass "refuses it" || fail "placeholder not caught (exit ${rc})"
cp monitoring/alertmanager.good monitoring/alertmanager.yml

echo
echo "a placeholder that is only named in a comment"
cp monitoring/alertmanager.good monitoring/alertmanager.yml
printf '# replace every REPLACE_WITH_ value\n' >> monitoring/alertmanager.yml
"${VERIFY}" > "${RIG}/out4b.log" 2>&1
grep -q "no placeholder left" "${RIG}/out4b.log" && pass "is not mistaken for one" || fail "a comment was counted as a placeholder"

echo
echo "a heartbeat file that is not a URL"
printf 'not a url\n' > monitoring/heartbeat-url
"${VERIFY}" > "${RIG}/out5.log" 2>&1; rc=$?
[ "${rc}" -eq 1 ] && grep -q "is missing, empty or not a URL" "${RIG}/out5.log" && pass "refuses it" || fail "bad heartbeat file not caught (exit ${rc})"

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "verify-monitoring.sh passed against the real monitoring stack."
