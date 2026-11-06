# OpenID Conformance Plan — Clavaris

🟡 En revisión (2026-11-05) — plan, not a result. Nothing in this document has been executed yet.

## 1. Why and what "done" means

`CLAUDE.md` §2 and `test-strategy.md` §4 make "passes the OpenID Foundation conformance suite for the Authorization Code flow with PKCE" a v1 exit criterion, and `definition-of-done.md` repeats it. The suite has never been run against Clavaris. Everything so far is our own tests of our own reading of the spec, which cannot find the bugs we share with ourselves.

**Done =** the three plans in §3 finish with every test `PASSED`, `WARNING` (each one explained in writing) or `SKIPPED` (each one justified by a feature Clavaris deliberately lacks), and zero `FAILED`; the exported results are archived under `docs/05-engineering/conformance-results/<date>/`. Formal certification (a listing with the OpenID Foundation, which has a fee and a licence step) is a separate, optional decision — see §9.

## 2. What the suite will see (facts from the repository)

| Aspect | Clavaris today | Consequence for the run |
|---|---|---|
| Issuer | Per Organization, path-based: `{base}/o/{organizationId}` | One Organization = one issuer under test. Use a dedicated Organization, never a real one. |
| Discovery | `/o/{id}/.well-known/openid-configuration`, asserted in `AuthorizationCodeFlowIntegrationTest` | Variant `server_metadata=discovery`. |
| Signing | RS256, per-Organization JWKS | Config plan checks `id_token_signing_alg_values_supported` contains RS256. |
| PKCE | `S256` only (`code_challenge_methods_supported = ["S256"]`) | Expected: tests that try `plain` must be refused, not accepted. |
| Client auth | `client_secret_basic`, `client_secret_post`; confidential clients only (ADR-0013) | Variant `client_registration=static_client`; no public-client or `private_key_jwt` tests. |
| Response types | `code` only | Implicit and hybrid plans are out of scope; they are not part of the Basic profile. |
| Dynamic registration | Not offered | Static clients only (§4). |
| Sign-in | Hosted, server-rendered, with optional email code, passkeys and TOTP per Organization | The suite drives a browser through login. The test Organization must use **password only**, with the code-after-password, TOTP and passkey switches off (§4). |
| Consent | Per-client, secure by default (ADR-0017) | Register the suite clients with `requireConsent: false`, and run one extra pass with it on to see the consent screen does not break the flow. |
| Sessions | Per-Organization cookie, path-scoped (ADR-0030) | Relevant to `prompt=none`, `max_age`, `id_token_hint` and logout tests. |
| Extra rate limiting | Two layers on login and `/oauth2/token` | The suite makes many rapid token and login calls; it may trip the limiter (§7, risk R2). |

## 3. Plans to run, in this order

Names are the suite's own identifiers as I know them; **confirm each against the suite's plan list on the day of the run**, since the suite is versioned and names or variants may have moved.

1. **Config OP** (`oidcc-config-certification-test-plan`) — reads only the discovery document and JWKS. Cheapest, finds metadata mistakes first. No browser.
2. **Basic OP** (`oidcc-basic-certification-test-plan`, variants `server_metadata=discovery`, `client_registration=static_client`, response type `code`) — the plan that matches the success metric. Needs the browser login.
3. **RP-Initiated Logout** (`oidcc-rp-initiated-logout-certification-test-plan`) — Clavaris advertises an end-session endpoint (`OidcLogoutWithoutHintFilter`, ADR-0030), so the suite should be asked to check it. Back-channel and front-channel logout are **not** supported and stay out of scope.

Not planned: Implicit, Hybrid, Form Post, Dynamic OP, FAPI, Third-Party-Initiated Login. Each is a deliberate absence (§2), recorded as "out of scope", not as a failure.

## 4. Preparation

1. **Where to run the suite.** The hosted suite at `certification.openid.net` is the simplest for self-testing; it needs the issuer to be reachable from the internet and needs a login with an OpenID-account. Running the suite yourself (Docker/Maven build from the Foundation's repository) is the alternative and is what a nightly CI job would use. Start hosted, decide on self-hosted later (§8).
2. **Target.** The preproduction instance, which is already public through the Tailscale Funnel + Caddy. Check before booking time: the issuer in the discovery document equals the public URL character for character (scheme, host, no trailing slash, `/o/{id}`); otherwise every plan fails on the first `iss` comparison for a reason that has nothing to do with conformance.
3. **A throwaway Organization** created through the platform API, named `conformance-<date>`. Policy: password sign-in only; email verification not required at sign-in; TOTP, passkeys, code-after-password and device trust off; self-registration closed.
4. **Two test accounts** in it, each with a verified email, a name and a `preferred_username` so claim tests have something to return. Passwords come from a secret store, never from the repository, and are not written into the archived results.
5. **Two static confidential clients** (the suite asks for two, because some tests compare behaviour across clients): `authorization_code` + `refresh_token`, `requireConsent: false`, redirect URIs exactly as the suite shows for the plan being run (`https://www.certification.openid.net/test/a/<alias>/callback`; the alias is chosen when creating the plan). Scopes: `openid profile email offline_access` as supported.
6. **Rate-limit headroom.** Leave the fixed anti-abuse thresholds alone (a tenant can never loosen them by design). Raise only the per-Organization capacity ceiling for the test Organization, and record that in the run notes.
7. **Quiet window.** Announce the run; the preproduction database is shared with nobody else today, but the signing key must not be rotated mid-run.
8. **Evidence of the starting point:** commit hash and image digest deployed, from `docker inspect`, in the run notes.

## 5. Execution

For each plan: create it in the suite with the discovery URL and the client credentials; run tests one by one first (the suite asks you to complete the browser step) and use "run all" only after the first pass is understood. Save the log of every test, the final plan page and the exported report to `conformance-results/<date>/<plan>/`.

Tests the suite marks as requiring a screenshot (login page, error pages) need one taken by hand — keep them with the logs.

## 6. Where I expect findings

Grounded in the code, not a guess about the suite; each is a place to look first when something fails:

| Area | Why it is a candidate |
|---|---|
| `prompt=none`, `prompt=login`, `max_age`, `id_token_hint` | Handled by custom filters (`OidcAuthorizationRequestParamsFilter`, `ReauthenticationMarker`), not by the framework alone. The suite checks the exact error (`login_required`, redirect with `state`) and `auth_time` in the ID token. |
| `auth_time`, `acr`, `amr` | Claims depend on how the person signed in; a mismatch with `max_age` is a classic failure. |
| `userinfo` | GET and POST with a bearer header, `sub` equal to the ID token's `sub`, claims by scope. The suite also checks that a request with a wrong or expired token gets `401` with `WWW-Authenticate`. |
| Code reuse | Second use of a code must fail and, per the spec's SHOULD, revoke the tokens it produced. |
| `redirect_uri` handling | Unregistered and mismatched URIs must **not** redirect (show an error page), and `state` must be returned unchanged on valid ones. |
| Unsupported request features | `request`, `request_uri` and `claims` parameters: the suite accepts "not supported" only if discovery says so (`request_parameter_supported`, `claims_parameter_supported`). |
| Login page | Language (`ui_locales`) and `login_hint` should be tolerated even if ignored. |
| End-session | Behaviour without `id_token_hint` (`OidcLogoutWithoutHintFilter`), `post_logout_redirect_uri` exact-match, `state` echo. |
| Rate limiting | A false positive from our own limiter would look like a server error in the suite (R2). |

## 7. Triage, fixing and re-running

For each non-passing test decide, in this order, and write the decision into the run notes:

1. **Our defect** — a real deviation from the spec. Open a `TD-SEC-`/`TD-ARCH-` row in `technical-debt-register.md` (security-relevant ones P1), fix with a failing test first, redeploy, re-run **the whole plan**, not just that test (a fix can regress another test).
2. **Configuration** — the Organization, client or discovery metadata was wrong for the test. Fix the setup, not the code.
3. **Suite behaviour we reasonably do not support** — document why in the results folder; this is the only category that may end as an explained `WARNING`/`SKIPPED`. Anything that is a MUST of the spec cannot be filed here.
4. **Suite defect** — rare; confirm against the spec text and the suite's issue tracker before claiming it.

Risks:

- **R1** Funnel throttling or a Caddy timeout looks like an OP failure. Mitigation: keep the Caddy and Funnel logs open during the run.
- **R2** The rate limiter rejects a legitimate burst. Mitigation: record the 429s in the run notes; if they are caused by the ceiling, raise the test Organization's ceiling, never the fixed threshold.
- **R3** The suite requires a person at the browser for each login test. Mitigation: budget the time (§10); automation is a later step.
- **R4** A pass on preproduction does not imply a pass on production if configuration differs. Mitigation: repeat the Config plan against production before the first consumer goes live.

## 8. After the first green run

- Add the archived report to the external-security-review package (`roadmap-and-release-plan.md`): the reviewer should see it.
- Tick the box in `definition-of-done.md` and update `roadmap-and-release-plan.md` §2 (second number: exit criterion) and `CLAUDE.md` §11 in the same change.
- Re-run the Config and Basic plans before every release that touches `OrganizationAuthorizationServerConfig`, the authorization/token filters or the Spring Authorization Server version. Automating this nightly against a disposable stack (the suite's own Docker setup, with browser steps scripted) is the register's "Published OpenID conformance results" item; only start it once a manual run has passed, because scripting an unknown failure set is wasted work.

## 9. Open decisions

1. **Decided (2026-11-05): self-testing only.** Self-testing is enough for the success metric and for the security review. Formal certification adds a listing and a fee; revisit only if the product becomes commercial (see the long-term commercialisation note in the vision document).
2. **Who sits at the browser.** One person, one half-day for Config + Basic, a second half-day for Logout and the re-run after fixes.
3. **Hosted or self-hosted suite** for the first run: recommended hosted.

## 10. Effort estimate

| Step | Estimate |
|---|---|
| Preparation (§4) | 0.5 day |
| Config plan + fixes | 0.5 day |
| Basic plan, first pass | 1 day |
| Fixes from findings (unknown until the first pass; typical for a first run: 3–8 findings) | 2–4 days |
| Logout plan | 0.5 day |
| Re-runs and archiving | 0.5 day |
| **Total** | **about 5–7 working days** |
