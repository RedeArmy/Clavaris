# Security audit — 2026-11-19 (13th whole-repository review)

🟡 En revisión

| | |
|---|---|
| **Author** | SDE-III review, requested explicitly (every file, frontend and backend, a security audit of the whole system) |
| **Basis** | `master` at `895600ba`, after the pull requests up to #344 |
| **Method** | Reading of the security configuration, the admin API and the authorization path; greps for known classes of defect over every module and every template and script; one hostile-input sweep of the admin API run against the real application (Postgres and Redis in containers); JaCoCo figures per module; the CI results of the last pull request. Not a penetration test, not a dependency scan (CI runs one on every pull request), and not a substitute for the external review CLAUDE.md section 6 makes mandatory. |
| **Result** | No P0 and no P1. One P2 and three P3 security-relevant findings, all in `technical-debt-register.md` (TD-ARCH-060, TD-ARCH-061, TD-SEC-104, TD-ARCH-062) and nothing that changes a trust boundary. |

## 1. What was checked and what was found

| Area | Check | Result |
|---|---|---|
| Injection and unsafe parsing | greps for `ObjectInputStream`, default typing, `XMLDecoder`, SQL built by concatenation in repository code | none present |
| Cryptography | greps for `Random`, `Math.random`, MD5, SHA-1 outside `SecureRandom` | none; Argon2id for passwords (ADR-0005) behind a bulkhead (`Argon2BulkheadPasswordEncoder`) so a burst of sign-ins cannot exhaust memory; passwords capped at 128 characters (`PasswordPolicy`) |
| CSRF | every `csrf` disablement | two, both on stateless bearer-token chains (the platform token endpoint and the admin API), each with its reasoning in the code; the session chains keep CSRF |
| Authorization | the `permitAll` sites; the admin API scope table; the ownership filter | `permitAll` only on the default public chain, the end-session path and the social-login callback chain, each documented; every mutating admin route has its own scope; an Organization's Secret Key is confined to an allowlist and to its own Organization (ADR-0023, ADR-0044), fail-closed |
| Rate limiting and abuse | the limiter and the idempotency store | both are single Lua scripts, atomic against concurrent clients, and fail open with a metric and an alert when Redis is down (TD-SEC-022) |
| SSRF | the webhook sender and URL registration | the resolved address is pinned for the connection (`PinnedAddresses`), private, loopback, link-local and metadata ranges are refused, and the shape of the URL is now checked before it is resolved (ADR-0047) |
| Frontend | inline scripts and handlers, third-party scripts, `localStorage`, `innerHTML`, `target=_blank` | no third-party script or stylesheet; one `innerHTML` assignment (`confirm-action.js`, static markup only, the text then set with `textContent`); `localStorage` only for a client-side submit lock; the vendored htmx (2.0.4) is watched by the vendored-files check |
| Information disclosure | the Swagger UI, the actuator, the error pages | the UI is off outside `dev`, `/actuator/**` is blocked at the edge by a regular expression that a script proves against the real `Caddyfile`, errors carry a correlation id and no detail |
| Secrets | `.env` handling, the bootstrap credential, the keystore | no secret in the repository; the bootstrap `PlatformClient` has no HTTP creation path (CLAUDE.md section 6) and its compromise has a runbook (`incident-response-platform-client-compromise.md`) |
| Input validation of the admin API | 175 malformed requests over 13 endpoints | **9 answered 5xx**, all in two endpoints: `PUT .../profile` (TD-ARCH-060) and `PUT .../social-login-policy` (TD-ARCH-061). The sweep looked for server errors only, not for hostile values that were accepted; the one such case seen is `phoneNumber`, which has no format check and stores any string (TD-ARCH-060) |
| Token lifecycle | revocation, logout, rotation and reuse | closed this month (TD-SEC-100, TD-SEC-101, TD-SEC-103); the residual is that an access token already issued outlives a revocation by its lifetime, five minutes, fixed for every client (TD-SEC-104) |
| Availability under dependency loss | Redis and Postgres outages | start-up, suspension, deletion and Organization erasure survive a Redis outage (ADR-0045); the degraded modes are not alerted on (TD-PROC-036) and two of their requirements are not checked at run time (TD-ARCH-062) |
| Supply chain | Dependabot, the Trivy gate, the SBOM and signed image, pinned action versions | all in place; the image build's Maven download can fail on a rate limit (TD-PROC-037); no licence check exists yet (TD-FUT-082) |
| Test evidence for security code | JaCoCo per package | `app` 97.0%, `identity-module` 94.6%; the DNS-pinning classes read 0% although they have tests, a measurement gap and not a missing test as far as is known (TD-TEST-020) |

## 2. Findings

| ID | Severity | What | Why it matters |
|---|---|---|---|
| TD-ARCH-060 | P2 | `PUT /api/v1/admin/accounts/{id}/profile`: 500 for a bad username and for long names; any phone number accepted | the route a consuming backend uses to write a profile; bad input is counted as a server error and invites a retry |
| TD-ARCH-061 | P3 | `PUT .../social-login-policy` with a `null` provider: 500 | same class, an administrator's request |
| TD-SEC-104 | P3 | access-token lifetime fixed at five minutes for every client | the bound of what revocation could not do; not tunable or documented |
| TD-ARCH-062 | P3 | Redis keyspace setting and listener health are invisible | a managed Redis without the setting silently stops announcing expired sessions |
| TD-TEST-019 | P2 | no test proves that malformed input never gives a 5xx | the same defect appeared three times in two weeks, one endpoint at a time |

## 3. What this audit cannot say

- It did not attack the running system from outside. The external review is the only thing that can, and it is still the gate.
- It did not read every one of the 1,806 production classes line by line; it read the security-critical paths and swept the rest by pattern and by behaviour, which is how the nine 5xx were found.
- It did not run a dependency scan or a secrets scan of its own; CI's Trivy, OWASP and Dependabot runs are the evidence for those.

## 4. Recommended order

1. TD-ARCH-060 and TD-ARCH-061 with TD-TEST-019, one change, because it ends a class of defect.
2. TD-PROC-036 with TD-ARCH-062, because the resilience of ADR-0045 is only worth what its alerts are.
3. Schedule the external security review. Nothing in this audit blocks it.

## 5. Update, same day

TD-ARCH-060, TD-ARCH-061 and TD-TEST-019 are closed (ADR-0048, BR-ADMIN-03): the profile validates its fields, a null among a list's elements is refused, and a sweep generated from the OpenAPI document keeps it so; the sweep found a third endpoint with the same defect (`POST .../secret-keys`), fixed with the others. TD-PROC-036 is closed too: the session-closure backlog, the Redis listeners, a failed key purge and a burst of graced reuse have alerts. Open from this audit: TD-SEC-104, TD-ARCH-062, TD-ARCH-063, TD-TEST-020, TD-PROC-037 and TD-FUT-082.
