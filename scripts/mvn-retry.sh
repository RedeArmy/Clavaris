#!/usr/bin/env bash
# Runs Maven and, when the run fails for a reason the network caused, runs it again (TD-PROC-038).
#
# Usage: ./scripts/mvn-retry.sh <the arguments you would give to mvn>
#
# Why this exists. Maven Central, SonarCloud and the NVD answer a shared CI runner's address with a 404
# for an artifact that exists, a 429 or a 503 now and then. A failed run costs a manual re-run, and the
# loop that used to retry the SonarCloud step could not help: Maven writes a `*.lastUpdated` file when an
# artifact is not found and, until it is deleted, answers every later attempt with "was not found ...
# during a previous attempt" without asking the network again. So attempts two and three failed in a
# second, and the run was lost to a failure that lasted one request.
#
# What it does, per attempt:
#   - runs `$MVN "$@"` (default `mvn`), showing the output as it comes and keeping a copy;
#   - on success, stops;
#   - on a failure that is not the network's (a compilation error, a failing test, a red Quality Gate),
#     stops at once with Maven's own exit code: running it again cannot change the answer;
#   - otherwise deletes the failure markers in the local repository, waits, and tries again. The second
#     attempt asks the Google mirror of Maven Central (infra/maven/settings-central-mirror.xml) with
#     strict checksums, the third asks Central again, so one host having a bad minute does not decide it.
#
# Which failures count as the network's depends on MVN_RETRY_MODE:
#   network (default)  only the ones Maven names in an [ERROR] line: could not transfer, not found in Central,
#                      timed out, connection reset, HTTP 429 or 5xx, a failed checksum. A [WARNING] about a
#                      repository that did not answer does not count.
#   any                every failure that is not on the "never retry" list below. For the SonarCloud
#                      analysis, whose own API fails in ways no pattern here can keep up with.
#
# Never retried, in either mode: a compilation error, a failing test, "QUALITY GATE STATUS: FAILED" and the
# dependency scan finding a vulnerability at or above its CVSS gate.
#
# Environment:
#   MVN                  the Maven command (default: mvn)
#   MVN_RETRY_ATTEMPTS   how many runs at most (default: 3)
#   MVN_RETRY_WAIT       seconds to wait after the first failure, doubled after the next (default: 20)
#   MVN_RETRY_MODE       network | any
#   MVN_LOCAL_REPO       the local repository (default: ~/.m2/repository)
#   MVN_MIRROR_SETTINGS  the settings file naming the mirror (default: infra/maven/settings-central-mirror.xml)
#
# The exit status is Maven's, from the last attempt.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MVN="${MVN:-mvn}"
ATTEMPTS="${MVN_RETRY_ATTEMPTS:-3}"
WAIT="${MVN_RETRY_WAIT:-20}"
MODE="${MVN_RETRY_MODE:-network}"
LOCAL_REPO="${MVN_LOCAL_REPO:-${HOME}/.m2/repository}"
MIRROR_SETTINGS="${MVN_MIRROR_SETTINGS:-${HERE}/../infra/maven/settings-central-mirror.xml}"

NEVER_RETRY='COMPILATION ERROR|Tests run:.*(Failures|Errors): [1-9]|There are test failures|QUALITY GATE STATUS: FAILED|CVSS score greater than or equal to'
NETWORK_FAILURE='Could not transfer (artifact|metadata)|Could not find artifact .* in central|No versions available for .* within specified range|was not found in .* during a previous attempt|Connection (reset|refused|timed out)|(Read|Connect) timed out|Premature end of Content-Length|SSLHandshakeException|Received fatal alert|Remote host terminated|status code: (429|5[0-9][0-9])|HTTP response code: (429|5[0-9][0-9])|Too Many Requests|Checksum validation failed|Could not resolve host|Temporary failure in name resolution'

case "${MODE}" in
  network | any) ;;
  *) echo "mvn-retry: MVN_RETRY_MODE must be 'network' or 'any', not '${MODE}'" >&2; exit 2 ;;
esac

# The caller chose its own settings file: it is not ours to replace.
caller_chose_settings=false
for arg in "$@"; do
  case "${arg}" in -s | --settings | --settings=* | -s=*) caller_chose_settings=true ;; esac
done

say() {
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    printf '::warning title=Maven retry::%s\n' "$1"
  else
    printf 'mvn-retry: %s\n' "$1" >&2
  fi
}

log="$(mktemp)"
trap 'rm -f "${log}"' EXIT

status=0
attempt=1
while true; do
  extra=()
  # Attempts 2, 4, ...: the mirror, with strict checksums. Odd attempts: whatever the build asks for.
  if ((attempt % 2 == 0)) && [[ "${caller_chose_settings}" == false && -f "${MIRROR_SETTINGS}" ]]; then
    extra=(--settings "${MIRROR_SETTINGS}" --strict-checksums)
    say "attempt ${attempt} of ${ATTEMPTS}: asking the Maven Central mirror"
  fi

  "${MVN}" "${extra[@]}" "$@" 2>&1 | tee "${log}"
  status="${PIPESTATUS[0]}"

  if ((status == 0)); then
    exit 0
  fi
  if grep -qE "${NEVER_RETRY}" "${log}"; then
    exit "${status}"
  fi
  # Only an [ERROR] line counts: a build that fails for its own reasons often carries a harmless
  # "[WARNING] Could not transfer metadata ... HTTP Status: 401" (an extra repository a plugin lists), and
  # reading that as a network failure retried a Spotless violation three times (2026-11-20).
  if [[ "${MODE}" == network ]] && ! grep -F '[ERROR]' "${log}" | grep -qE "${NETWORK_FAILURE}"; then
    exit "${status}"
  fi
  if ((attempt >= ATTEMPTS)); then
    say "gave up after ${attempt} attempts (exit ${status})"
    exit "${status}"
  fi

  say "attempt ${attempt} of ${ATTEMPTS} failed (exit ${status}); clearing Maven's failure markers and trying again"
  # Without this, the next attempt is answered from the cache of the failure and never asks the network.
  if [[ -d "${LOCAL_REPO}" ]]; then
    find "${LOCAL_REPO}" \( -name '*.lastUpdated' -o -name 'resolver-status.properties' \) -type f -delete 2>/dev/null || true
  fi
  sleep $((WAIT * (1 << (attempt - 1))))
  attempt=$((attempt + 1))
done
