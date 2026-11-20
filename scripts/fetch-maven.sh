#!/usr/bin/env bash
# TD-PROC-037: fetches the pinned Maven distribution, verified, into a directory. Used by the image
# build (app/Dockerfile) and by every CI job that needs Maven without the wrapper, so there is one
# implementation of the part that failed a pull request with HTTP 429.
#
# Why not ./mvnw: it downloads the distribution from Maven Central with no retry, Central rate-limits
# shared runner addresses, and the failure comes before any compilation. It also cannot verify what it
# downloaded on both platforms (it fetches the .tar.gz on Linux and the .zip on Windows, and checks
# one checksum against whichever it got).
#
# What this does: reads distributionUrl and distributionTarGzSha256Sum from
# maven-wrapper.properties, so the version is still bumped in one place, and fetches the .tar.gz,
# retrying with a growing wait, from Maven Central, then Google's mirror of it, then Apache's archive.
# Whichever host answers, the archive is used only if it matches the checksum, so an unreliable or even
# a hostile mirror can delay the build but not change what it runs. Needs curl, sha256sum and tar.
#
# Usage: fetch-maven.sh <destination-dir>        (the Maven home ends up there: <dir>/bin/mvn)
# Environment, all optional:
#   MAVEN_PROPERTIES                path of maven-wrapper.properties (default: the repository's own)
#   MAVEN_FETCH_ATTEMPTS            rounds over the three hosts (default 4)
#   MAVEN_FETCH_BACKOFF_SECONDS     wait after round n is n times this (default 10)
#   MAVEN_CENTRAL_BASE              replaces the first host's base URL (used by the tests)

set -euo pipefail

destination="${1:?usage: fetch-maven.sh <destination-dir>}"
props="${MAVEN_PROPERTIES:-$(dirname "$0")/../.mvn/wrapper/maven-wrapper.properties}"
attempts="${MAVEN_FETCH_ATTEMPTS:-4}"
backoff="${MAVEN_FETCH_BACKOFF_SECONDS:-10}"

prop() { sed -n "s/^$1=//p" "${props}" | tr -d '[:space:]'; }

url="$(prop distributionUrl)"
sha="$(prop distributionTarGzSha256Sum)"
if [ -z "${url}" ] || [ -z "${sha}" ]; then
    echo "${props} lacks distributionUrl or distributionTarGzSha256Sum" >&2
    exit 1
fi

archive="$(basename "${url}" .zip).tar.gz"
version="${archive#apache-maven-}"
version="${version%-bin.tar.gz}"
central="${MAVEN_CENTRAL_BASE:-${url%/org/apache/maven/*}}"
path="org/apache/maven/apache-maven/${version}/${archive}"
sources=(
    "${central}/${path}"
    "https://maven-central.storage-download.googleapis.com/maven2/${path}"
    "https://archive.apache.org/dist/maven/maven-3/${version}/binaries/${archive}"
)

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

attempt=1
while :; do
    for source in "${sources[@]}"; do
        if curl -fsSL --connect-timeout 20 --max-time 180 -o "${work}/maven.tar.gz" "${source}" \
            && echo "${sha}  ${work}/maven.tar.gz" | sha256sum -c - >/dev/null 2>&1; then
            break 2
        fi
        echo "Maven ${version} not usable from ${source} (round ${attempt})" >&2
    done
    if [ "${attempt}" -ge "${attempts}" ]; then
        echo "Maven ${version} could not be fetched and verified from any host" >&2
        exit 1
    fi
    sleep $((attempt * backoff))
    attempt=$((attempt + 1))
done

mkdir -p "${destination}"
tar -xzf "${work}/maven.tar.gz" -C "${destination}" --strip-components=1
"${destination}/bin/mvn" -B -v
