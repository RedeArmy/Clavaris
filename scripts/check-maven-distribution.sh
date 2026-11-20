#!/usr/bin/env bash
# TD-PROC-037: the image build fetches the Maven distribution itself (a retried, mirrored, checksum
# verified step in app/Dockerfile) instead of letting ./mvnw download it into the cache mount, which
# failed a pull request with HTTP 429 from Maven Central. That only holds while three things stay
# true, none of which a build would notice until the cache next missed:
#   1. maven-wrapper.properties carries dockerDistributionSha256Sum, 64 hex digits, the checksum the
#      Dockerfile verifies the .tar.gz against;
#   2. it does NOT carry distributionSha256Sum: mvnw fetches the .tar.gz on Linux and macOS and the
#      .zip on Windows, so a single value breaks mvnw on one of them;
#   3. the Dockerfile builds with the Maven it unpacked (/opt/maven/bin/mvn), not with ./mvnw, which
#      would download a second one, unverified, into the cache mount;
#   4. every workflow that runs ./mvnw caches ~/.m2/wrapper, which setup-java's `cache: maven` does
#      not, or each run downloads the distribution from Maven Central again.
# Static on purpose: checking the value against the real archive would make this job depend on the
# network it exists to stop depending on. A bump of distributionUrl that leaves the checksum behind
# stops the image build with a checksum error, loudly, instead.
#
# Usage: check-maven-distribution.sh [repository-root]   Exit code 0 = clean; non-zero = see stderr.

set -uo pipefail
root="${1:-$(dirname "$0")/..}"
cd "${root}" || exit 1

props=".mvn/wrapper/maven-wrapper.properties"
dockerfile="app/Dockerfile"
status=0
complain() {
    echo "$1" >&2
    status=1
}

[ -f "${props}" ] || { echo "missing ${props}" >&2; exit 1; }
[ -f "${dockerfile}" ] || { echo "missing ${dockerfile}" >&2; exit 1; }

if ! grep -Eq '^dockerDistributionSha256Sum=[0-9a-f]{64}$' "${props}"; then
    complain "${props}: dockerDistributionSha256Sum must be set to 64 lowercase hex digits (the SHA-256 of the .tar.gz the image build downloads)"
fi
if grep -Eq '^distributionSha256Sum=' "${props}"; then
    complain "${props}: distributionSha256Sum must not be set; mvnw would check one value against the .tar.gz on Linux/macOS and the .zip on Windows"
fi
if grep -Eq '^[[:space:]]*RUN[^#]*\./mvnw' "${dockerfile}" || grep -Eq '^[[:space:]]+[^#[:space:]].*\./mvnw[[:space:]]' "${dockerfile}"; then
    complain "${dockerfile}: build with /opt/maven/bin/mvn, not ./mvnw, which downloads its own unverified Maven"
fi
if ! grep -q '/opt/maven/bin/mvn' "${dockerfile}"; then
    complain "${dockerfile}: the build stage must run the Maven it unpacked (/opt/maven/bin/mvn)"
fi
if ! grep -q 'dockerDistributionSha256Sum' "${dockerfile}"; then
    complain "${dockerfile}: the Maven fetch must verify the archive against dockerDistributionSha256Sum"
fi

for workflow in .github/workflows/*.yml; do
    [ -f "${workflow}" ] || continue
    if grep -Eq '\./mvnw[[:space:]]' "${workflow}" && ! grep -q 'maven-wrapper-\$' "${workflow}"; then
        complain "${workflow}: runs ./mvnw without caching ~/.m2/wrapper (key maven-wrapper-<hash of maven-wrapper.properties>)"
    fi
done

[ "${status}" -eq 0 ] && echo "OK: the image build's Maven distribution is fetched and verified (TD-PROC-037)."
exit "${status}"
