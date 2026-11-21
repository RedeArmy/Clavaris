# Security audit — 2026-11-20 (14th whole-repository review)

🟡 En revisión

| | |
|---|---|
| **Author** | SDE-III review, requested explicitly (every file, frontend, backend and workers; an audit of the whole system) |
| **Basis** | `master` at `95a88f67`, after the pull requests up to #354 |
| **Method** | Reading and measuring, not running: greps over every module, template, script, workflow and compose file for known classes of defect; a read of the security configuration, the scheduler and the outbound HTTP clients; a comparison of the compose files against a container-hardening baseline; a check of what the register already says before anything was written down. No penetration test, no dynamic scan and no hostile-input sweep was run in this review (see §3). |
| **Result** | No P0 and no P1. Two P2 and three P3 security findings, plus one P2 and one P3 that touch security indirectly, all in `technical-debt-register.md` (TD-SEC-105 to 108, TD-ARCH-064, TD-PROC-039, TD-PERF-066) and future rows TD-FUT-084 to 087. Nothing changes a trust boundary of the application; the gaps are around it: how the containers run, which database role the application holds, and what the pipeline does not look for. |

## 1. What was checked and what was found

| Area | Check | Result |
|---|---|---|
| Injection and unsafe parsing | SQL built by concatenation in repository code (`jdbcTemplate`, native queries) | none; the `jdbcTemplate` calls (the token-revoker and grants bridges) join string literals only and bind every value with `?` |
| Cryptography | `new Random`, `Math.random`, `ThreadLocalRandom` | one use, webhook retry jitter, with a comment saying why it is not a secret (`DeliverPendingWebhooksService`); nothing else |
| Comparison of secrets | `.equals(` on hashes, tokens, signatures, codes | none in production code |
| CSRF | every `csrf` disablement | two, both on stateless bearer-token chains, as the 13th review found; unchanged |
| Authorization | every `permitAll` site | the default public chain, the end-session path, the social-login callback chain, the dashboard's public paths; each carries its reasoning |
| Output encoding | `th:utext`, inline `on*=` handlers, inline `<script>`, `style=` attributes, JS sinks (`innerHTML`, `eval`, `document.write`) | `th:utext` none; `style=` none; one `innerHTML` assignment (`confirm-action.js`, static markup, text then set with `textContent`); the two templates that mention an inline `onclick`, `onchange` or `<script>` do so only in comments explaining why they were removed |
| Browser hardening | CSP, Permissions-Policy, Referrer-Policy, HSTS, frame-ancestors | the CSP is built per route class in `ContentSecurityPolicies` with `frame-ancestors 'none'` except for an embedded client's own origin; `Permissions-Policy` and `Referrer-Policy` filters exist; HSTS is set at the edge (`max-age=31536000; includeSubDomains`, no `preload`, a choice and not a defect while the issuer hostnames are not yet fixed) |
| Outbound HTTP | every `HttpClient` and `RestClient` | timeouts and a circuit breaker on social login and on the webhook sender (redirects off, address pinned); `GitHubVerifiedEmailUserService` builds `HttpClient.newHttpClient()` (no connect timeout) but sets a per-request timeout, so the exposure is the connect phase only, noted under TD-PERF-066 |
| Concurrency and scheduling | `@Scheduled`, executors, `synchronized`, `Thread.sleep` | scheduler pool sized at 5 (TD-PERF-003); every nightly sweep takes the advisory lock; the three jobs that do not (the two pending-closure drains and the Redis keyspace check) are idempotent and queue-based; bounded named pools for webhooks, new-device mail and the mail pools; no `Thread.sleep` in production code |
| Supply chain of the pipeline | action pinning, `permissions`, `pull_request_target`, secrets | every action is pinned by commit SHA with its tag in a comment; a top-level `permissions` block; no `pull_request_target`; the two secrets used by jobs (`NVD_API_KEY`, `SONAR_TOKEN`) are scoped to their steps. What is absent: a secret scanner, a CodeQL or Semgrep pass and a dynamic scan (TD-PROC-039) |
| Containers and the host | `read_only`, `cap_drop`, `no-new-privileges`, `pids_limit`, `cpus`, `mem_limit`, `user` in `docker-compose.prod.yml` and `docker-compose.preprod.yml` | **none of the first five is set on any service**; `mem_limit` only on `app` and the two Redis services; the app image drops to a non-root user (uid 10001) and the Dockerfile removes `pebble`; Caddy, Postgres and the Redis services run as the images ship them (TD-SEC-105) |
| Database role | which role the application connects as | the container's bootstrap role: `POSTGRES_USER` is `DB_PROD_USER` and the application's `DB_DEV_USER` is set to the same value in the production compose file, so migrations and runtime queries use one role that owns every table and, being created by the image's init, is a superuser (TD-SEC-106) |
| Redis | authentication | no `requirepass`, no ACL; `REDIS_PASSWORD` in `application.yml` defaults to empty and the production compose file does not set it; the only barrier is the internal Docker network (TD-SEC-107) |
| Request size | Tomcat, Spring, Caddy | multipart is capped at 11 MB for every endpoint (the avatar's 10 MB plus a margin) and the edge sets no `request_body` limit; Tomcat's form limit applies to form posts only (TD-SEC-108) |
| Events and webhooks | the shape of what a consumer receives | `Clavaris-Event-Type`, `-Event-Id`, `-Delivery-Id`, `-Signature` and `-Trace-Id` headers; the body carries no schema version and the headers carry none either (TD-ARCH-064) |
| Recovery | backup mechanism | `pg_dump -F c` once a day at 02:00 to the same disk (TD-FUT-029 covers the second half); no write-ahead-log archiving, so the recovery point is up to a day (TD-FUT-085) |
| Code hygiene as a proxy for review depth | `NOSONAR`, `@SuppressWarnings`, `TODO`/`FIXME`, `@Disabled`, empty `catch`, `printStackTrace`, `System.out` | 7, 191, 0 in production code, 0, 0, 0, 0. The 191 `@SuppressWarnings` are the figure to watch (TD-PROC-039 notes it); the largest production class is 546 lines |

## 2. Findings

| ID | Severity | Where | What an attacker or an incident gets |
|---|---|---|---|
| TD-SEC-105 | P2 | `docker-compose.prod.yml`, `docker-compose.preprod.yml` | Remote code execution in the application, or in Caddy, lands in a container with every default Linux capability, a writable root file system, the ability to gain privileges through a setuid binary and no limit on processes. Each switch is one line and none changes behaviour that a test covers today. |
| TD-SEC-106 | P2 | `docker-compose.prod.yml` (the `app` service environment), `application.yml` (`spring.datasource`, `spring.flyway`) | A SQL injection or a code-execution bug runs as the owner of every table: it can rewrite the audit log, drop a table or, as a superuser, read files and run programs on the database host. A runtime role that may only read and write rows would turn all of those into a smaller incident. |
| TD-ARCH-064 | P2 | `DeliverPendingWebhooksService`, the outbox event classes | Not an attack. The first consumer that parses a payload will break the day a field is renamed, and the platform cannot tell it which shape it received. Cheap now, a flag day later. |
| TD-PROC-039 | P2 | `.github/workflows` | A committed secret is found by the first person who looks at the history, not by the pipeline; a class of defect SonarCloud's rules do not cover is not looked for; nothing exercises the running application from outside. |
| TD-SEC-107 | P3 | `docker-compose.prod.yml`, `application.yml` | Anything on the Docker network can read and change sessions, rate-limit counters and idempotency records. It needs a foothold on the network first, which is why this is P3 and why it is still worth one line of configuration. |
| TD-SEC-108 | P3 | `Caddyfile`, `application.yml` | Authenticated endpoints accept bodies as large as the heap lets Spring read; the multipart cap of 11 MB applies to every multipart endpoint, not only the avatar. |
| TD-PERF-066 | P3 | `SigningKeyStore`, `GitHubVerifiedEmailUserService` | Not an attack. A cold cache after a restart serialises every organization's first token on one monitor and one file read each; the GitHub client waits without bound for a connection. Neither measured yet. |

## 3. What this audit cannot say

- It ran nothing against a live application. The 13th review's hostile-input sweep (175 requests, 9 server errors) was not repeated, and the 14th did not extend it to the endpoints added since; TD-PROC-039 proposes making that a recurring job.
- It did not read all 1,800 production source files line by line; it measured with greps and read the files where a grep pointed. A grep proves the absence of a pattern, not the absence of a flaw.
- The pre-production host was not inspected. Everything about containers is from the compose files, not from `docker inspect`; the first step of TD-SEC-105 is to read what the running containers actually have.
- Cryptographic design (Argon2id parameters, the envelope format, token lifetimes) was taken from the existing ADRs and reviews, not re-derived.
- This is not the external security review the roadmap makes a hard gate. It makes that review cheaper, and it does not replace it.

## 4. Recommended order

1. TD-SEC-106 and TD-SEC-105 together, in one pull request, because both change the compose files and the deploy script and both need the same live check on pre-production. Do them before the external review: a reviewer's first two questions are "what does the application connect as" and "what can a compromised container do".
2. TD-PROC-039. The secret scanner is an hour; CodeQL is half a day; the dynamic scan against pre-production is the larger job and the most valuable one.
3. TD-ARCH-064 before the first real consumer integrates a webhook receiver (TD-FUT-080 is the same trigger).
4. TD-SEC-107 and TD-SEC-108 as part of the next compose change.
5. TD-PERF-066 only when a measurement says so.

## 5. Update, same day

The two P2 findings of the first recommendation, the pipeline finding and the contract finding were fixed the same day, in the order of section 4 (the resolutions are in `technical-debt-register.md` section 6, the decisions in ADR-0051 and ADR-0052):

| Finding | What changed | Evidence |
|---|---|---|
| TD-SEC-106 | The application connects as `clavaris_app` (rows only, audit log append-only), Flyway as `clavaris_owner`, neither a superuser; roles provisioned on every deploy and after a restore; the two partition statements became `SECURITY DEFINER` functions | 15 integration tests against PostgreSQL 16 starting from the state of a host that already runs; the SQL also run by hand on a scratch database; the scripts' stand-in tests; the migration under the full Spring context |
| TD-SEC-105 | `cap_drop: ALL`, `no-new-privileges`, process limits, read-only root and memory limits on every production service, `cap_drop` and `no-new-privileges` on monitoring | The four pinned images started with exactly those flags; the application image reached readiness read-only with no capability; found on the way that Redis 8 needs `SETPCAP` |
| TD-TEST-022 | `check-container-hardening.sh` and its 17-case test, in the `lint` job | Run against the real compose files and against mutated copies |
| TD-PROC-039 | gitleaks (commits, history, working tree), CodeQL for Java and JavaScript, ZAP baseline in the e2e job | gitleaks over the history of the repository (1,093 commits, 23.8 MB): 12 findings, every one a test fixture, a spike's placeholder or a conformance module name, none a credential, now listed one by one in `.gitleaks.toml`; the working tree scans clean with it; the ZAP baseline against a running instance: 61 rules passed, no FAIL, 3 warnings, 4 ignored |
| TD-ARCH-064 | `Clavaris-Event-Version` on every delivery, a test that pins the JSON of all 33 event records, a contract page for integrators | The test fails on a missing, changed or stale sample; the delivery test pins the header |

What the work found that this audit had not:

- `mvn-retry.sh` retried a Spotless violation three times because a harmless `[WARNING] Could not transfer metadata ... 401` (an extra repository one plugin lists) matched its network pattern. Only `[ERROR]` lines count now, with a test.
- A deploy does not recreate Postgres or Redis (by design), so the containers that exist keep the old flags until recreated once; `deploy.sh` now says so on every deploy while it is true.
- `sync-deploy-files.sh`, the CD job's sparse checkout and `bootstrap.sh` each keep their own list of the deployment files, so a new file that `deploy.sh` and `restore-postgres.sh` depend on had to be added to all three; the CD job's list would have made the first deploy fail at the checkout.

Still open from this audit: TD-SEC-107 (Redis has no password), TD-SEC-108 (no body limit at the edge), TD-PERF-066 (measure first), the new TD-SEC-109, TD-SEC-110 and TD-PROC-040, and the future rows TD-FUT-084 to 088.

Needs the owner, and cannot be done from the repository: add `DB_APP_PASSWORD` and `DB_OWNER_PASSWORD` to the pre-production `.env` **before** the next deploy (it refuses before pulling anything without them); switch on GitHub's secret scanning and push protection for the repository (they are off); and decide when a green `security-scan` workflow becomes a required check.
