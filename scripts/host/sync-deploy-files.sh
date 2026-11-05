#!/usr/bin/env bash
# TD-PROC-022: the host runs its own copies of deploy.sh and docker-compose.prod.yml, and nothing
# refreshed them, so a newer image that needed a new variable met a compose file that never passed
# it and crash-looped for hours (2026-11-05). The CD job now runs this, from the checkout of the
# commit it is deploying, before deploy.sh: it installs the deployment files from that commit into
# the deployment directory, prints what changed, and never touches .env.
#
# Usage: sync-deploy-files.sh <source-checkout> <deployment-dir>
#
# - Files are installed atomically (written beside the target, then renamed), so a deploy.sh that is
#   being replaced is never half-written.
# - docker-compose.preprod.yml and Caddyfile.preprod are refreshed only on a host that already has
#   them: that file's presence is how deploy.sh decides a host is pre-production, so this script
#   must never make a production host look like one.
# - .env is never read for its values or written; only the NAMES it defines are compared with
#   .env.example, to say which variable a new compose file may be about to require.
# - Nothing is deleted.

set -euo pipefail

SRC="${1:-}"
DEST="${2:-}"
if [ -z "${SRC}" ] || [ -z "${DEST}" ]; then
  echo "Usage: $0 <source-checkout> <deployment-dir>" >&2
  exit 1
fi
[ -d "${SRC}" ] || { echo "Source checkout not found: ${SRC}" >&2; exit 1; }
[ -d "${DEST}" ] || { echo "Deployment directory not found: ${DEST}" >&2; exit 1; }

changed=0

# install_file <path in the checkout> <name in the deployment dir> <mode> [only-if-present]
install_file() {
  local from="${SRC}/$1" to="${DEST}/$2" mode="$3" only_if_present="${4:-}"
  if [ ! -f "${from}" ]; then
    echo "Missing from the checkout: $1" >&2
    exit 1
  fi
  if [ "${only_if_present}" = "only-if-present" ] && [ ! -f "${to}" ]; then
    echo "skip       $2 (this host does not use it)"
    return 0
  fi
  if [ -f "${to}" ] && cmp -s "${from}" "${to}"; then
    echo "unchanged  $2"
    return 0
  fi
  if [ -f "${to}" ]; then
    echo "changed    $2"
    diff -u "${to}" "${from}" | sed 's/^/    /' || true
  else
    echo "new        $2"
  fi
  install -m "${mode}" "${from}" "${to}.new"
  mv "${to}.new" "${to}"
  changed=$((changed + 1))
}

install_file docker-compose.prod.yml docker-compose.prod.yml 644
install_file Caddyfile Caddyfile 644
install_file .env.example .env.example 644
install_file docker-compose.preprod.yml docker-compose.preprod.yml 644 only-if-present
install_file Caddyfile.preprod Caddyfile.preprod 644 only-if-present
install_file scripts/host/deploy.sh deploy.sh 755
install_file scripts/host/backup-postgres.sh backup-postgres.sh 755
install_file scripts/host/restore-postgres.sh restore-postgres.sh 755

# Names only: a variable .env.example defines and .env lacks is the usual reason a newer image will
# not start. deploy.sh's own pre-flight check is what actually refuses to continue.
if [ -f "${DEST}/.env" ]; then
  missing="$(comm -23 \
    <(grep -oE '^[A-Z][A-Z0-9_]*=' "${DEST}/.env.example" | tr -d '=' | sort -u) \
    <(grep -oE '^[[:space:]]*(export[[:space:]]+)?[A-Z][A-Z0-9_]*=' "${DEST}/.env" | sed -E 's/^[[:space:]]*(export[[:space:]]+)?//; s/=//' | sort -u))"
  if [ -n "${missing}" ]; then
    echo
    echo "NOTE: .env.example defines variables that .env does not:"
    echo "${missing}" | sed 's/^/    /'
    echo "Some are optional. deploy.sh refuses to continue if the compose file requires one of them."
  fi
fi

echo
echo "${changed} file(s) updated in ${DEST}."
