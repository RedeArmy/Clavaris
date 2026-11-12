#!/usr/bin/env bash
# Tests for check-vendored-assets.sh against a throwaway manifest and a directory standing in for the
# npm registry, so they need no network. Usage: ./scripts/test-check-vendored-assets.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${HERE}/check-vendored-assets.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

printf 'the vendored bytes' > "${WORK}/lib.min.js"
SHA="$(sha256sum "${WORK}/lib.min.js" | cut -d' ' -f1)"
write_manifest() { # version sha
  cat > "${WORK}/manifest.json" <<JSON
{"assets": [{"name": "lib", "file": "${WORK}/lib.min.js", "version": "$1", "sha256": "$2", "npm": "lib"}]}
JSON
}
registry() { # latest version
  mkdir -p "${WORK}/registry/lib"
  printf '{"version": "%s"}' "$1" > "${WORK}/registry/lib/latest"
}
run() { VENDORED_MANIFEST="${WORK}/manifest.json" NPM_REGISTRY="file://${WORK}/registry" "${CHECK}" "$@" > "${WORK}/out" 2>&1; RC=$?; }

echo "a file that matches its recorded hash"
write_manifest 1.0.0 "${SHA}"; registry 1.0.0
run
[ "${RC}" -eq 0 ] && pass "passes" || fail "exit code ${RC}"
run --latest
[ "${RC}" -eq 0 ] && pass "passes with --latest when it is the newest" || fail "exit code ${RC} with --latest"

echo "a file edited by hand"
printf 'the vendored bytes, edited' > "${WORK}/lib.min.js"
run
[ "${RC}" -eq 1 ] && pass "fails with 1" || fail "exit code ${RC}"
grep -q "CHANGED" "${WORK}/out" && pass "says it changed" || fail "no explanation"
printf 'the vendored bytes' > "${WORK}/lib.min.js"

echo "a file that is not there"
write_manifest 1.0.0 "${SHA}"
mv "${WORK}/lib.min.js" "${WORK}/gone.js"
run
[ "${RC}" -eq 1 ] && pass "fails with 1" || fail "exit code ${RC}"
grep -q "MISSING" "${WORK}/out" && pass "says it is missing" || fail "no explanation"
mv "${WORK}/gone.js" "${WORK}/lib.min.js"

echo "a newer release on the registry"
registry 1.2.0
run
[ "${RC}" -eq 0 ] && pass "is not looked at without --latest" || fail "exit code ${RC} without --latest"
run --latest
[ "${RC}" -eq 3 ] && pass "ends with 3" || fail "exit code ${RC}, expected 3"
grep -q "OUTDATED  lib: 1.0.0 is vendored, 1.2.0" "${WORK}/out" && pass "names both versions" || fail "no versions in the message"

echo "a registry that cannot be read"
rm -rf "${WORK}/registry"
run --latest
[ "${RC}" -eq 1 ] && pass "fails rather than reporting nothing" || fail "exit code ${RC}, expected 1"

echo "the real manifest"
"${CHECK}" > "${WORK}/real" 2>&1 && pass "every vendored file matches" || { fail "the real manifest does not match"; cat "${WORK}/real"; }

echo
if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed."
  exit 1
fi
echo "All check-vendored-assets checks passed."
