# Conformance results, 2026-11-20

OpenID Foundation conformance suite v5.3.4 (the Foundation's prebuilt images, run on a developer machine) against the **pre-production** instance over HTTPS:

- Issuer: `https://preproduction.tail5e07f4.ts.net/o/bdc995c3-d5e6-4ae6-a1dc-afdcfc9e6a6e`, a throwaway Organization with a password-only policy, two static confidential clients (`pkcePolicy: OPTIONAL`, since the Basic OP plan sends no PKCE) and two test accounts.
- Plans: Config OP, Basic OP (35 modules) and RP-Initiated Logout (11 modules). Each module ran on a freshly restarted suite.
- This is the self-test the plan's §1 describes, **not a formal certification** (no listing with the Foundation).
- The commit and image digest deployed on pre-production at the time were **not recorded** (plan §4, item 8).

## Reading the results

- **PASSED** is a pass. **WARNING** and **SKIPPED** are each explained in `../../openid-conformance-plan.md` §11. **REVIEW** means the module ends on a page a person has to judge (an error page, a confirmation page, a second sign-in page); the suite stored the page's source as evidence, since its scripted browser cannot produce an image. Nothing here is FAILED except the one superseded run noted below.
- **The superseded FAILED.** `oidcc-ensure-request-with-acr-values-succeeds` was interrupted once because the suite's request for the discovery document got a `502 Bad Gateway` from the Tailscale edge before the test began. It was run again and passed; both logs are kept.
- `no-id-token-hint` (RP-Initiated Logout) shows the "Sign out?" confirmation by design (ADR-0030); the suite expects an error page, so a reviewer decides.

## What the logs contain

Each file is the suite's log of one module with secrets removed: every value under a key naming a secret, password, token, assertion, cookie or authorization header; the plan's configuration; JWTs; `Bearer`/`Basic` credentials; and `code`, `access_token`, `id_token` and `refresh_token` in query strings. The client secrets and account passwords were also searched for, value by value, across all files, and none was found. The `client_id`s and the test accounts' `@example.test` addresses stay: they are not secrets, and the Organization and its accounts exist only for these runs.

`summary.json` lists module, test id, status, result and counts of each condition's result.

## Results

### Config OP (`oidcc-config-certification-test-plan`)

PASSED: 1

| Module | Result | Log |
|---|---|---|
| `oidcc-discovery-endpoint-verification` | PASSED | [oidcc-discovery-endpoint-verification-a3yb23nv2OUVRNZ.json](config-op/oidcc-discovery-endpoint-verification-a3yb23nv2OUVRNZ.json) |

### Basic OP (`oidcc-basic-certification-test-plan`)

FAILED: 1, PASSED: 24, REVIEW: 5, SKIPPED: 3, WARNING: 3

| Module | Result | Log |
|---|---|---|
| `oidcc-alternate-happy-flow` | PASSED | [oidcc-alternate-happy-flow-eC7669JVSf2Uego.json](basic-op/oidcc-alternate-happy-flow-eC7669JVSf2Uego.json) |
| `oidcc-claims-essential` | WARNING | [oidcc-claims-essential-S5SzgDetVpxBqNY.json](basic-op/oidcc-claims-essential-S5SzgDetVpxBqNY.json) |
| `oidcc-claims-locales` | PASSED | [oidcc-claims-locales-BFwbdgqnq5XBuVG.json](basic-op/oidcc-claims-locales-BFwbdgqnq5XBuVG.json) |
| `oidcc-codereuse` | PASSED | [oidcc-codereuse-vDx28xEoMoTX30t.json](basic-op/oidcc-codereuse-vDx28xEoMoTX30t.json) |
| `oidcc-codereuse-30seconds` | PASSED | [oidcc-codereuse-30seconds-gzcuqLwhEsKBMfP.json](basic-op/oidcc-codereuse-30seconds-gzcuqLwhEsKBMfP.json) |
| `oidcc-display-page` | PASSED | [oidcc-display-page-MDUFAf6k6FmC9PP.json](basic-op/oidcc-display-page-MDUFAf6k6FmC9PP.json) |
| `oidcc-display-popup` | PASSED | [oidcc-display-popup-MmdxZdqFhkKH3yZ.json](basic-op/oidcc-display-popup-MmdxZdqFhkKH3yZ.json) |
| `oidcc-ensure-post-request-succeeds` | PASSED | [oidcc-ensure-post-request-succeeds-bLvhJxzTSL7QDK3.json](basic-op/oidcc-ensure-post-request-succeeds-bLvhJxzTSL7QDK3.json) |
| `oidcc-ensure-registered-redirect-uri` | REVIEW | [oidcc-ensure-registered-redirect-uri-Y5UNBmHUCgggCVo.json](basic-op/oidcc-ensure-registered-redirect-uri-Y5UNBmHUCgggCVo.json) |
| `oidcc-ensure-request-object-with-redirect-uri` | REVIEW | [oidcc-ensure-request-object-with-redirect-uri-r38nfCpMcTB6oND.json](basic-op/oidcc-ensure-request-object-with-redirect-uri-r38nfCpMcTB6oND.json) |
| `oidcc-ensure-request-with-acr-values-succeeds` | PASSED | [oidcc-ensure-request-with-acr-values-succeeds-HGaS5THo0cYMx2i.json](basic-op/oidcc-ensure-request-with-acr-values-succeeds-HGaS5THo0cYMx2i.json) |
| `oidcc-ensure-request-with-acr-values-succeeds` | FAILED (superseded, see note) | [oidcc-ensure-request-with-acr-values-succeeds-cAgv4KYqlattE2e.json](basic-op/oidcc-ensure-request-with-acr-values-succeeds-cAgv4KYqlattE2e.json) |
| `oidcc-ensure-request-with-unknown-parameter-succeeds` | PASSED | [oidcc-ensure-request-with-unknown-parameter-succeeds-zqL6qeZYz3xwh4c.json](basic-op/oidcc-ensure-request-with-unknown-parameter-succeeds-zqL6qeZYz3xwh4c.json) |
| `oidcc-ensure-request-with-valid-pkce-succeeds` | PASSED | [oidcc-ensure-request-with-valid-pkce-succeeds-MsbI4TbwF2gRqYt.json](basic-op/oidcc-ensure-request-with-valid-pkce-succeeds-MsbI4TbwF2gRqYt.json) |
| `oidcc-ensure-request-without-nonce-succeeds-for-code-flow` | PASSED | [oidcc-ensure-request-without-nonce-succeeds-for-code-flow-HCKsCUYq3n18oAQ.json](basic-op/oidcc-ensure-request-without-nonce-succeeds-for-code-flow-HCKsCUYq3n18oAQ.json) |
| `oidcc-id-token-hint` | PASSED | [oidcc-id-token-hint-S52Em2f7Dm6Xb4v.json](basic-op/oidcc-id-token-hint-S52Em2f7Dm6Xb4v.json) |
| `oidcc-login-hint` | PASSED | [oidcc-login-hint-aP49fLYEIWkj8hz.json](basic-op/oidcc-login-hint-aP49fLYEIWkj8hz.json) |
| `oidcc-max-age-1` | REVIEW | [oidcc-max-age-1-bU50dmXIiJnGq58.json](basic-op/oidcc-max-age-1-bU50dmXIiJnGq58.json) |
| `oidcc-max-age-10000` | PASSED | [oidcc-max-age-10000-1j0GqUvU4ND4dl0.json](basic-op/oidcc-max-age-10000-1j0GqUvU4ND4dl0.json) |
| `oidcc-prompt-login` | REVIEW | [oidcc-prompt-login-06t1802syluzceh.json](basic-op/oidcc-prompt-login-06t1802syluzceh.json) |
| `oidcc-prompt-none-logged-in` | PASSED | [oidcc-prompt-none-logged-in-s12V63Gx7fZnVAy.json](basic-op/oidcc-prompt-none-logged-in-s12V63Gx7fZnVAy.json) |
| `oidcc-prompt-none-not-logged-in` | PASSED | [oidcc-prompt-none-not-logged-in-jRfbULe7zz7ZCsE.json](basic-op/oidcc-prompt-none-not-logged-in-jRfbULe7zz7ZCsE.json) |
| `oidcc-refresh-token` | PASSED | [oidcc-refresh-token-11VJjnmJ3sp0maU.json](basic-op/oidcc-refresh-token-11VJjnmJ3sp0maU.json) |
| `oidcc-response-type-missing` | REVIEW | [oidcc-response-type-missing-OpVQOGn3BQJ0Zc8.json](basic-op/oidcc-response-type-missing-OpVQOGn3BQJ0Zc8.json) |
| `oidcc-scope-address` | SKIPPED | [oidcc-scope-address-yMSsAezNYrdIFXV.json](basic-op/oidcc-scope-address-yMSsAezNYrdIFXV.json) |
| `oidcc-scope-all` | SKIPPED | [oidcc-scope-all-YgzV7s0s3C7LPr0.json](basic-op/oidcc-scope-all-YgzV7s0s3C7LPr0.json) |
| `oidcc-scope-email` | PASSED | [oidcc-scope-email-gw3qorwOdLQQHg3.json](basic-op/oidcc-scope-email-gw3qorwOdLQQHg3.json) |
| `oidcc-scope-phone` | PASSED | [oidcc-scope-phone-KbRLSBBI0PhL5fg.json](basic-op/oidcc-scope-phone-KbRLSBBI0PhL5fg.json) |
| `oidcc-scope-profile` | WARNING | [oidcc-scope-profile-OLCoqn6aLRvOEwY.json](basic-op/oidcc-scope-profile-OLCoqn6aLRvOEwY.json) |
| `oidcc-server` | PASSED | [oidcc-server-Wr2eFFZ17woPLAr.json](basic-op/oidcc-server-Wr2eFFZ17woPLAr.json) |
| `oidcc-server-client-secret-post` | PASSED | [oidcc-server-client-secret-post-yj9FT6pJiPXZ7Q6.json](basic-op/oidcc-server-client-secret-post-yj9FT6pJiPXZ7Q6.json) |
| `oidcc-ui-locales` | PASSED | [oidcc-ui-locales-0Um4IQ0Cd9XrALG.json](basic-op/oidcc-ui-locales-0Um4IQ0Cd9XrALG.json) |
| `oidcc-unsigned-request-object-supported-correctly-or-rejected-as-unsupported` | SKIPPED | [oidcc-unsigned-request-object-supported-correctly-or-rejected-as-unsupported-nxj1WKFUGIfh6dO.json](basic-op/oidcc-unsigned-request-object-supported-correctly-or-rejected-as-unsupported-nxj1WKFUGIfh6dO.json) |
| `oidcc-userinfo-get` | PASSED | [oidcc-userinfo-get-iiCTg6nXUU3Q3nZ.json](basic-op/oidcc-userinfo-get-iiCTg6nXUU3Q3nZ.json) |
| `oidcc-userinfo-post-body` | WARNING | [oidcc-userinfo-post-body-1h6RFgRomwAk9kt.json](basic-op/oidcc-userinfo-post-body-1h6RFgRomwAk9kt.json) |
| `oidcc-userinfo-post-header` | PASSED | [oidcc-userinfo-post-header-iWuM2EW4zYMnK2F.json](basic-op/oidcc-userinfo-post-header-iWuM2EW4zYMnK2F.json) |

### RP-Initiated Logout (`oidcc-rp-initiated-logout-certification-test-plan`)

PASSED: 3, REVIEW: 8

| Module | Result | Log |
|---|---|---|
| `oidcc-rp-initiated-logout` | PASSED | [oidcc-rp-initiated-logout-NtTCiCmaPOQ9JNV.json](rp-initiated-logout/oidcc-rp-initiated-logout-NtTCiCmaPOQ9JNV.json) |
| `oidcc-rp-initiated-logout-bad-id-token-hint` | REVIEW | [oidcc-rp-initiated-logout-bad-id-token-hint-oMqnZLnSJnuUHhL.json](rp-initiated-logout/oidcc-rp-initiated-logout-bad-id-token-hint-oMqnZLnSJnuUHhL.json) |
| `oidcc-rp-initiated-logout-bad-post-logout-redirect-uri` | REVIEW | [oidcc-rp-initiated-logout-bad-post-logout-redirect-uri-MaTWUWFP9vj9i3w.json](rp-initiated-logout/oidcc-rp-initiated-logout-bad-post-logout-redirect-uri-MaTWUWFP9vj9i3w.json) |
| `oidcc-rp-initiated-logout-discovery-endpoint-verification` | PASSED | [oidcc-rp-initiated-logout-discovery-endpoint-verification-HaXNSK2BLh4Lygm.json](rp-initiated-logout/oidcc-rp-initiated-logout-discovery-endpoint-verification-HaXNSK2BLh4Lygm.json) |
| `oidcc-rp-initiated-logout-modified-id-token-hint` | REVIEW | [oidcc-rp-initiated-logout-modified-id-token-hint-eSoZRISac3SpH9q.json](rp-initiated-logout/oidcc-rp-initiated-logout-modified-id-token-hint-eSoZRISac3SpH9q.json) |
| `oidcc-rp-initiated-logout-no-id-token-hint` | REVIEW | [oidcc-rp-initiated-logout-no-id-token-hint-PWmi1yKFjJqS7oQ.json](rp-initiated-logout/oidcc-rp-initiated-logout-no-id-token-hint-PWmi1yKFjJqS7oQ.json) |
| `oidcc-rp-initiated-logout-no-params` | REVIEW | [oidcc-rp-initiated-logout-no-params-4t5yOjzTZbSabkE.json](rp-initiated-logout/oidcc-rp-initiated-logout-no-params-4t5yOjzTZbSabkE.json) |
| `oidcc-rp-initiated-logout-no-post-logout-redirect-uri` | REVIEW | [oidcc-rp-initiated-logout-no-post-logout-redirect-uri-LI4HzJozoaHX0O9.json](rp-initiated-logout/oidcc-rp-initiated-logout-no-post-logout-redirect-uri-LI4HzJozoaHX0O9.json) |
| `oidcc-rp-initiated-logout-no-state` | PASSED | [oidcc-rp-initiated-logout-no-state-HeCrmST9WiuI3gU.json](rp-initiated-logout/oidcc-rp-initiated-logout-no-state-HeCrmST9WiuI3gU.json) |
| `oidcc-rp-initiated-logout-only-state` | REVIEW | [oidcc-rp-initiated-logout-only-state-fNPmVJdj48Rxlz0.json](rp-initiated-logout/oidcc-rp-initiated-logout-only-state-fNPmVJdj48Rxlz0.json) |
| `oidcc-rp-initiated-logout-query-added-to-post-logout-redirect-uri` | REVIEW | [oidcc-rp-initiated-logout-query-added-to-post-logout-redirect-uri-tVjl042GMd2zAIY.json](rp-initiated-logout/oidcc-rp-initiated-logout-query-added-to-post-logout-redirect-uri-tVjl042GMd2zAIY.json) |
