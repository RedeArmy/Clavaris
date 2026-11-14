#!/bin/sh
# Runs Lighthouse CI over one set of pages and one profile (see lighthouserc.cjs for the environment).
# Meant for the Playwright image, which already carries a Chromium; locally, set CHROME_PATH instead.
#   LH_SET=public|operator  LH_PRESET=desktop|mobile  LH_ASSERT=1 to fail below the floors
# Results are left in $LH_OUT (default .lighthouseci) as one lhr-*.json per run.
set -e
cd "$(dirname "$0")"
if [ -z "$CHROME_PATH" ]; then
  CHROME_PATH="$(node -e "console.log(require('../node_modules/@playwright/test').chromium.executablePath())")"
  export CHROME_PATH
fi
OUT="${LH_OUT:-.lighthouseci}"
rm -rf .lighthouseci
npx -y @lhci/cli@0.15.1 collect --config=lighthouserc.cjs
if [ "$LH_OUT" ] && [ "$LH_OUT" != ".lighthouseci" ]; then
  mkdir -p "$OUT" && cp .lighthouseci/lhr-*.json "$OUT/"
fi
if [ -n "$LH_ASSERT" ]; then
  npx -y @lhci/cli@0.15.1 assert --config=lighthouserc.cjs
fi
