#!/usr/bin/env bash
# Tests for mvn-retry.sh (TD-PROC-038) against a stand-in `mvn` that fails the way a run is told to.
#
# Usage: ./scripts/test-mvn-retry.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RETRY="${HERE}/mvn-retry.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { # description, condition (0 = ok)
  if [[ "$2" == 0 ]]; then pass "$1"; else fail "$1"; fi
}

# The stand-in: each call appends its arguments to $CALLS, then answers from the next line of $SCRIPT,
# "<exit status>|<text to print>"; when the script runs out it answers with its last line.
cat > "${WORK}/mvn" <<'E'
#!/usr/bin/env bash
n=$(($(wc -l < "${CALLS}") + 1))
printf '%s\n' "$*" >> "${CALLS}"
line="$(sed -n "${n}p" "${SCRIPT}")"
[[ -z "${line}" ]] && line="$(tail -n 1 "${SCRIPT}")"
printf '%s\n' "${line#*|}"
exit "${line%%|*}"
E
chmod +x "${WORK}/mvn"

REPO="${WORK}/repo"
MIRROR="${WORK}/mirror-settings.xml"
echo "<settings/>" > "${MIRROR}"

# run <script lines...>: runs mvn-retry with the stand-in; sets $status and $calls (how many times mvn ran).
run() {
  : > "${WORK}/calls"
  printf '%s\n' "$@" > "${WORK}/script"
  mkdir -p "${REPO}/a"
  : > "${REPO}/a/x.pom.lastUpdated"
  MVN="${WORK}/mvn" CALLS="${WORK}/calls" SCRIPT="${WORK}/script" MVN_RETRY_WAIT=0 \
    MVN_LOCAL_REPO="${REPO}" MVN_MIRROR_SETTINGS="${MIRROR}" "${RETRY_ENV[@]}" "${RETRY}" "${ARGS[@]}" > /dev/null 2>&1
  status=$?
  calls="$(wc -l < "${WORK}/calls")"
}
RETRY_ENV=(env)
ARGS=(-B verify)

echo "mvn-retry.sh"

# 0. The mirror's settings file is well-formed XML and names the mirror. (Maven refuses a settings file it
# cannot parse, which a stand-in mvn would never notice: a "--" inside a comment once did exactly that.)
SETTINGS="${HERE}/../infra/maven/settings-central-mirror.xml"
PYTHON="$(command -v python3 || command -v python)"
"${PYTHON}" - "${SETTINGS}" <<'PY' > /dev/null 2>&1
import sys, xml.etree.ElementTree as ET
ns = {"s": "http://maven.apache.org/SETTINGS/1.2.0"}
m = ET.parse(sys.argv[1]).getroot().find("s:mirrors/s:mirror", ns)
sys.exit(0 if m is not None and m.findtext("s:mirrorOf", namespaces=ns) == "central" else 1)
PY
check "the mirror settings file parses and maps central" "$?"

# 1. A build that passes runs once.
run '0|BUILD SUCCESS'
check "a passing build runs once" "$(( calls == 1 && status == 0 ? 0 : 1 ))"

# 2. A transfer failure is retried, the failure markers are cleared, and the second run asks the mirror.
run '1|[ERROR] Could not transfer artifact org.x:y:pom:1 from/to central (https://repo.maven.apache.org/maven2): status code: 503' '0|BUILD SUCCESS'
check "a transfer failure is retried and then passes" "$(( calls == 2 && status == 0 ? 0 : 1 ))"
check "the failure markers are deleted before the retry" "$([[ ! -e "${REPO}/a/x.pom.lastUpdated" ]] && echo 0 || echo 1)"
check "the second run asks the mirror with strict checksums" "$(sed -n 2p "${WORK}/calls" | grep -q -- "--settings ${MIRROR} --strict-checksums -B verify" && echo 0 || echo 1)"
check "the first run is the plain command" "$([[ "$(sed -n 1p "${WORK}/calls")" == "-B verify" ]] && echo 0 || echo 1)"

# 3. The cached failure that sank the SonarCloud step is retried too.
run '1|[ERROR] Plugin org.s:p:5.1 was not found in https://repo.maven.apache.org/maven2 during a previous attempt.' '0|ok'
check "'during a previous attempt' is retried" "$(( calls == 2 && status == 0 ? 0 : 1 ))"

# 4. A Central 404 for an artifact is retried.
run '1|[ERROR] Could not find artifact org.s:p:jar:5.1 in central (https://repo.maven.apache.org/maven2)' '0|ok'
check "'could not find artifact ... in central' is retried" "$(( calls == 2 && status == 0 ? 0 : 1 ))"

# 5. Failures that are not the network's are not retried, and the exit status is Maven's.
run '1|[ERROR] COMPILATION ERROR :'
check "a compilation error is not retried" "$(( calls == 1 && status == 1 ? 0 : 1 ))"
run '3|Tests run: 10, Failures: 1, Errors: 0, Skipped: 0'
check "a failing test is not retried and keeps its exit status" "$(( calls == 1 && status == 3 ? 0 : 1 ))"
run '1|[ERROR] Some plugin said no, for its own reasons'
check "in network mode an unknown failure is not retried" "$(( calls == 1 && status == 1 ? 0 : 1 ))"
run '1|[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to 7.0
[WARNING] Could not transfer artifact x: status code: 503'
check "a vulnerability found by the dependency scan is not retried" "$(( calls == 1 ? 0 : 1 ))"
# ... even when a test failure sits next to a network word.
run '1|Tests run: 5, Failures: 1, Errors: 0
Could not transfer artifact x'
check "a failing test wins over a network message" "$(( calls == 1 ? 0 : 1 ))"

# 6. It gives up after the attempts, with the last exit status, and the third attempt is not the mirror's.
run '1|Could not transfer artifact a: status code: 429' '1|Could not transfer artifact a: status code: 429' '7|Could not transfer artifact a: status code: 429'
check "it stops after three attempts" "$(( calls == 3 ? 0 : 1 ))"
check "and returns the last attempt's exit status" "$(( status == 7 ? 0 : 1 ))"
check "the third attempt asks Central again, not the mirror" "$([[ "$(sed -n 3p "${WORK}/calls")" == "-B verify" ]] && echo 0 || echo 1)"
RETRY_ENV=(env MVN_RETRY_ATTEMPTS=5)
run '1|Could not transfer artifact a: status code: 429'
check "MVN_RETRY_ATTEMPTS changes the number of attempts" "$(( calls == 5 ? 0 : 1 ))"
RETRY_ENV=(env)

# 7. A settings file the caller chose is left alone.
ARGS=(-B -s my-settings.xml verify)
run '1|Could not transfer artifact a: status code: 503' '0|ok'
check "the caller's own settings file is never replaced" "$(sed -n 2p "${WORK}/calls" | grep -q -- "--settings" && echo 1 || echo 0)"
ARGS=(-B verify)

# 8. Mode any: an unknown failure is retried, a red Quality Gate is not.
RETRY_ENV=(env MVN_RETRY_MODE=any)
run '1|[ERROR] Error status returned by url [https://sonarcloud.io/api/settings/values.protobuf]: 503' '0|ok'
check "mode any retries a failure no pattern names" "$(( calls == 2 && status == 0 ? 0 : 1 ))"
run '1|[ERROR] QUALITY GATE STATUS: FAILED - View details on https://sonarcloud.io/dashboard?id=x'
check "mode any does not retry a red Quality Gate" "$(( calls == 1 && status == 1 ? 0 : 1 ))"
RETRY_ENV=(env)

# 9. A wrong mode is refused before anything runs.
RETRY_ENV=(env MVN_RETRY_MODE=sometimes)
run '0|ok'
check "an unknown MVN_RETRY_MODE is refused" "$(( status == 2 && calls == 0 ? 0 : 1 ))"
RETRY_ENV=(env)

echo
if ((failures > 0)); then
  printf '%d check(s) failed\n' "${failures}"
  exit 1
fi
echo "all checks passed"
