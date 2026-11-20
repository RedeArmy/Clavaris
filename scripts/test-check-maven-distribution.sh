#!/usr/bin/env bash
# Tests check-maven-distribution.sh against a copy of the repository's own two files, with each
# property it guards broken in turn. Run by CI next to the check itself.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

check="$(pwd)/scripts/check-maven-distribution.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
failures=0

fresh() {
    rm -rf "${work}/repo"
    mkdir -p "${work}/repo/.mvn/wrapper" "${work}/repo/app" "${work}/repo/scripts"
    cp .mvn/wrapper/maven-wrapper.properties "${work}/repo/.mvn/wrapper/"
    cp scripts/fetch-maven.sh "${work}/repo/scripts/"
    cp app/Dockerfile "${work}/repo/app/"
    mkdir -p "${work}/repo/.github/workflows"
    cp .github/workflows/*.yml "${work}/repo/.github/workflows/"
}

expect() { # expect <pass|fail> <description>
    "${check}" "${work}/repo" >/dev/null 2>&1
    local code=$?
    if { [ "$1" = pass ] && [ "${code}" -ne 0 ]; } || { [ "$1" = fail ] && [ "${code}" -eq 0 ]; }; then
        echo "FAIL: $2 (expected $1, exit ${code})" >&2
        failures=$((failures + 1))
    fi
}

fresh; expect pass "the repository as it is"

fresh; sed -i '/^distributionTarGzSha256Sum=/d' "${work}/repo/.mvn/wrapper/maven-wrapper.properties"
expect fail "no docker checksum"

fresh; sed -i 's/^distributionTarGzSha256Sum=.*/distributionTarGzSha256Sum=abc123/' "${work}/repo/.mvn/wrapper/maven-wrapper.properties"
expect fail "a checksum that is not 64 hex digits"

fresh; echo "distributionSha256Sum=$(printf '0%.0s' {1..64})" >> "${work}/repo/.mvn/wrapper/maven-wrapper.properties"
expect fail "the wrapper checksum, which breaks one platform"

fresh; printf 'RUN ./mvnw -B package\n' >> "${work}/repo/app/Dockerfile"
expect fail "the Dockerfile building with the wrapper"

fresh; sed -i 's#/opt/maven/bin/mvn#mvn#g' "${work}/repo/app/Dockerfile"
expect fail "the Dockerfile not using the unpacked Maven"

fresh; sed -i 's#bash /tmp/fetch-maven.sh#bash /tmp/something-else.sh#; s#fetch-maven.sh /opt/maven#else.sh /opt/maven#' "${work}/repo/app/Dockerfile"
expect fail "the Dockerfile not fetching Maven with fetch-maven.sh"

fresh; sed -i 's/distributionTarGzSha256Sum/somethingElse/g' "${work}/repo/scripts/fetch-maven.sh"
expect fail "fetch-maven.sh not checking the archive against the checksum"

fresh; printf '      - run: ./mvnw -B package\n' > "${work}/repo/.github/workflows/new.yml"
expect fail "a workflow running ./mvnw"

fresh; printf '      - run: ./mvnw -B package\n' > "${work}/repo/.github/workflows/new.yml"
sed -i 's#./mvnw#mvn#' "${work}/repo/.github/workflows/new.yml"
expect pass "a workflow running mvn after fetch-maven.sh"

if [ "${failures}" -eq 0 ]; then echo "OK: check-maven-distribution.sh behaves."; fi
exit "${failures}"
