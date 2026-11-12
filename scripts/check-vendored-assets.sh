#!/usr/bin/env bash
# TD-PROC-033: files copied into the repository by hand (htmx, the Geist fonts) are invisible to
# Dependabot and to the dependency scan, so nothing tells anyone when a new release carries a fix, or
# when a copy was edited. vendored-assets.json records each one's version, source and SHA-256.
#
#   check-vendored-assets.sh            every file still matches the SHA-256 recorded for it
#   check-vendored-assets.sh --latest   also asks the npm registry whether a newer release exists
#                                       (only for assets that record an "npm" package)
#
# Exit codes: 0 clean, 1 a file is missing or does not match (or the manifest is unreadable),
#             3 everything matches but a newer release exists (--latest only).
# NPM_REGISTRY overrides the registry URL (the tests point it at a directory of files).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

MANIFEST="${VENDORED_MANIFEST:-vendored-assets.json}"
REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
latest=false
[ "${1:-}" = "--latest" ] && latest=true

[ -f "${MANIFEST}" ] || { echo "No manifest: ${MANIFEST}" >&2; exit 1; }

status=0
outdated=0
# One line per asset: name|file|version|sha256|npm
while IFS='|' read -r name file version sha npm; do
  if [ ! -f "${file}" ]; then
    echo "MISSING   ${name}: ${file}" >&2
    status=1
    continue
  fi
  actual="$(sha256sum "${file}" | cut -d' ' -f1)"
  if [ "${actual}" != "${sha}" ]; then
    echo "CHANGED   ${name}: ${file} is ${actual}, the manifest records ${sha} (${version})." >&2
    echo "          Edited by hand? Update vendored-assets.json with the new version and hash." >&2
    status=1
    continue
  fi
  echo "ok        ${name} ${version}"
  if [ "${latest}" = true ] && [ -n "${npm}" ]; then
    newest="$(curl -fsS -m 20 "${REGISTRY}/${npm}/latest" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])' 2>/dev/null || true)"
    if [ -z "${newest}" ]; then
      echo "UNKNOWN   ${name}: could not read the latest version from ${REGISTRY}" >&2
      status=1
    elif [ "${newest}" != "${version}" ]; then
      echo "OUTDATED  ${name}: ${version} is vendored, ${newest} is the latest on npm (${npm})."
      outdated=1
    fi
  fi
done < <(python3 - "${MANIFEST}" <<'PY'
import json, sys
for a in json.load(open(sys.argv[1]))["assets"]:
    print("|".join([a["name"], a["file"], a["version"], a["sha256"], a.get("npm", "")]))
PY
)

[ "${status}" -ne 0 ] && exit 1
[ "${outdated}" -ne 0 ] && exit 3
exit 0
