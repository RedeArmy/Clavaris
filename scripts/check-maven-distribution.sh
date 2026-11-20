#!/usr/bin/env bash
# TD-PROC-037: the image build and the CI jobs that need Maven fetch it with scripts/fetch-maven.sh
# (retried, from three hosts, checked against a checksum) instead of letting ./mvnw download it from
# Maven Central, which failed a pull request with HTTP 429 and, on a cold cache, cannot be retried.
# That only holds while these stay true, none of which a build would notice until a cache next missed:
#   1. maven-wrapper.properties carries distributionTarGzSha256Sum, 64 hex digits, the checksum
#      fetch-maven.sh verifies the .tar.gz against;
#   2. it does NOT carry distributionSha256Sum: mvnw fetches the .tar.gz on Linux and macOS and the
#      .zip on Windows, so a single value breaks mvnw on one of them;
#   3. the Dockerfile fetches Maven with fetch-maven.sh and builds with /opt/maven/bin/mvn, never with
#      ./mvnw, which would download a second one, unverified, into the cache mount;
#   4. no workflow runs ./mvnw: a job that needs Maven runs fetch-maven.sh and puts it on the PATH;
#   5. fetch-maven.sh itself checks the archive against distributionTarGzSha256Sum.
# Static on purpose: checking the value against the real archive would make this job depend on the
# network it exists to stop depending on. A bump of distributionUrl that leaves the checksum behind
# stops the fetch with a checksum error, loudly, instead.
#
# Usage: check-maven-distribution.sh [repository-root]   Exit code 0 = clean; non-zero = see stderr.

set -uo pipefail
root="${1:-$(dirname "$0")/..}"
cd "${root}" || exit 1

props=".mvn/wrapper/maven-wrapper.properties"
dockerfile="app/Dockerfile"
fetch="scripts/fetch-maven.sh"
status=0
complain() {
    echo "$1" >&2
    status=1
}

for required in "${props}" "${dockerfile}" "${fetch}"; do
    [ -f "${required}" ] || { echo "missing ${required}" >&2; exit 1; }
done

if ! grep -Eq '^distributionTarGzSha256Sum=[0-9a-f]{64}$' "${props}"; then
    complain "${props}: distributionTarGzSha256Sum must be set to 64 lowercase hex digits (the SHA-256 of the .tar.gz that ${fetch} downloads)"
fi
if grep -Eq '^distributionSha256Sum=' "${props}"; then
    complain "${props}: distributionSha256Sum must not be set; mvnw would check one value against the .tar.gz on Linux/macOS and the .zip on Windows"
fi
if grep -Eq '^[[:space:]]*RUN[^#]*\./mvnw' "${dockerfile}" || grep -Eq '^[[:space:]]+[^#[:space:]].*\./mvnw[[:space:]]' "${dockerfile}"; then
    complain "${dockerfile}: build with /opt/maven/bin/mvn, not ./mvnw, which downloads its own unverified Maven"
fi
if ! grep -q '/opt/maven/bin/mvn' "${dockerfile}"; then
    complain "${dockerfile}: the build stage must run the Maven it fetched (/opt/maven/bin/mvn)"
fi
if ! grep -Eq '^[[:space:]]+bash /tmp/fetch-maven\.sh|fetch-maven\.sh /opt/maven' "${dockerfile}"; then
    complain "${dockerfile}: the Maven must be fetched with fetch-maven.sh, which retries and verifies it"
fi
if ! grep -q 'distributionTarGzSha256Sum' "${fetch}" || ! grep -q 'sha256sum -c' "${fetch}"; then
    complain "${fetch}: must check the archive against distributionTarGzSha256Sum"
fi
for workflow in .github/workflows/*.yml; do
    [ -f "${workflow}" ] || continue
    if grep -Eq '^[[:space:]]*(-[[:space:]]+)?(run:[[:space:]]+)?\./mvnw[[:space:]]|^[[:space:]]+\./mvnw[[:space:]]' "${workflow}"; then
        complain "${workflow}: runs ./mvnw, which downloads Maven from Maven Central with no retry; use scripts/fetch-maven.sh and put it on the PATH"
    fi
done

[ "${status}" -eq 0 ] && echo "OK: Maven is fetched with retries and a checksum everywhere it is needed (TD-PROC-037)."
exit "${status}"
