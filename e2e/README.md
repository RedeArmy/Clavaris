# Browser tests (TD-TEST-008)

Playwright tests that drive a running Clavaris instance in a real browser, under the real
Content-Security-Policy. They exist because the policy has no inline script or style allowance, and
a script, style or handler it blocks fails no server-side test: the control just silently does
nothing. That class of defect was found live three times (TD-FUT-034, TD-ARCH-031, TD-UX-001), and
the first run of this suite found two more (TD-FUT-051, and htmx's injected `<style>` on every
dashboard page).

Every test runs through the `watched` page in `fixtures.ts`, which fails the test on any CSP
violation, uncaught script error or failed same-origin request, whatever the test was checking.

| Spec | What it covers |
|---|---|
| `hosted-sign-in.spec.ts` | the page every consuming application sends its users to: renders styled, its script runs, a PKCE sign-in returns an authorization code, a wrong password leaves the form usable |
| `dashboard-scripts.spec.ts` | the page scripts as the build ships them (minified, TD-FUT-051): walks up to 60 pages of the operator dashboard by following its links, fails on any CSP violation, script error or failed request, and checks the served scripts really are the minified ones |
| `dashboard.spec.ts` | the operator dashboard: sign-in session, htmx present, creating a Workspace swaps the list in place, and the operator's own Manage account and Sessions pages load the shell's scripts |

## Running locally

```bash
# 1. an instance (docker compose up, or java -jar app/target/clavaris-app.jar with the dev profile)
# 2. seed it, and export what it prints
export CLAVARIS_BASE_URL=http://localhost:8080 \
       PLATFORM_BOOTSTRAP_CLIENT_ID=... PLATFORM_BOOTSTRAP_CLIENT_SECRET=...
eval "$(./e2e/seed.sh)"
# 3. run
cd e2e && npm ci && npx playwright install chromium && npx playwright test
```

`seed.sh` registers an operator and makes that account's email verified with one `UPDATE` through
`psql` (override `PSQL` if the database is not the compose one), creates an Organization and a
client, and registers two end users. The `+clavaris_test` tag in the emails makes the instance skip
every real send. Nothing here talks to a mail provider.

The suite signs the operator in once (`auth.setup.ts`) and reuses the session: the login limiter is
real, and ten sign-ins in five minutes would lock the account out of its own tests. A failing run
leaves a trace in `test-results/`; `npx playwright show-trace <file>` replays it.

This package is separate from `app/package.json`, which deliberately has no dependencies: that suite
tests one small script with Node's built-in runner, this one needs a browser.
