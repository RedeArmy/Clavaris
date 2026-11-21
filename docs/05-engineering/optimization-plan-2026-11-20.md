# Optimisation, concurrency and bottleneck plan — 2026-11-20

🟡 En revisión

| | |
|---|---|
| **Author** | SDE-III review (the 14th whole-repository review, see `technical-debt-register.md`) |
| **Basis** | `master` at `95a88f67`; numbers quoted from the register, `load-testing/README.md` and `application.yml`, each with its row; nothing in this document was measured in this review |
| **Purpose** | Say where the system will give out first, in what order to look, what to measure before changing anything, and which of the product's strengths are worth defending. A plan, not a commitment: the owner of the roadmap schedules it |

## 1. How to read this

The system is a modular monolith on one VM (Spring Boot, Postgres 16, two Redis instances, Caddy) with a documented target of 99.5% availability and a p95 of 300 ms. Its expensive operations are few and known, and a previous review measured them. This plan therefore does not propose to speed everything up. It ranks the places where a **measured** limit exists, says what would move it, and lists what to measure before spending effort anywhere else.

Rules this plan keeps, because the register's own history shows what happens without them: measure first (the virtual-threads experiment in TD-FUT-046 lifted a cap and made the sign-in flow slower, p95 7 s to 27 s); change one thing per measurement; keep the number and the command that produced it next to the change (`load-testing/results`).

## 2. Where the system gives out, in order

| # | Limit | What is known | Source | What would move it |
|---|---|---|---|---|
| 1 | **Argon2id on the token and sign-in path** | About 16 to 19 MiB of working memory per concurrent verification; `/oauth2/token` collapsed to a 14.2 s p95 at concurrency 30 on the 3-vCPU reference machine. Tomcat is capped at 50 threads so the worst case is bounded (about 1 GiB), and an `Argon2BulkheadPasswordEncoder` admits a limited number at once | TD-FUT-017, TD-PERF-007, `application.yml` (`server.tomcat.threads.max`) | Admission control by endpoint (done as the bulkhead), then a measured choice of the bulkhead size against vCPU count; a faster hash only through the parameters decision of ADR-0005, never by loosening them for speed. Client-credential secrets are high-entropy: a fast keyed hash for them is a decision for an ADR, not a tuning knob |
| 2 | **The JDBC pool of ten** | Capped on purpose; thirty concurrent sign-ins at 500 ms each exhaust it; `connection-timeout` 10 s so exhaustion is loud | TD-FUT-017, TD-FUT-046, TD-FUT-049 | Keep connections out of slow calls (TD-PERF-031 is the row for a transaction held across an HTTP call; check its status in section 2 or 6 of the register before relying on it); then a pool in front of Postgres (PgBouncer, transaction mode) interacts with advisory locks, so those first move to a session-pinned path or a lock table |
| 3 | **Start-up: 67 to 89 s to healthy on the two-vCPU host, no class-data cache** | Measured with `scripts/measure-startup.sh` on the layered image | TD-PERF-064 | AppCDS (a class-data archive built in the image) is the cheap step; lazy initialisation of the beans a request does not need is the second; neither changes a request path. Matters for every deploy window until TD-FUT-020 gives a second instance |
| 4 | **The build: about 26 minutes** | 91 test classes start their own Postgres container and Spring context | TD-PROC-012 | Share containers and contexts (measured once and left, per the row); the safe order is split by module in the pipeline, then reuse of one container per module through a base class, then Develocity-style test distribution only if the first two are not enough |
| 5 | **Signing-key store on a cache miss** | One global monitor, a PKCS12 load per call; hidden by a cache | TD-PERF-066 (new) | A cold-start measurement with 500 Organizations decides whether a lock per scope is worth it |
| 6 | **A single-instance Postgres and Redis** | One of each; the second Redis exists to keep counters apart (ADR-0050) | TD-FUT-049, TD-FUT-020 | Read replicas for the dashboard's read-only pages are the first step and are cheap once the read paths are already separated by use case; not before the pool saturates |
| 7 | **The webhook dispatcher** | A bounded pool of named platform threads; two ticks every five seconds; the scheduler pool is five | TD-PERF-003, TD-PERF-004, `WebhookUseCaseConfig` | Only when the delivery backlog metric (the queues are on the metrics endpoint, §7 of the register) shows a standing queue; the knobs exist and are named |

What the review looked for and did **not** find: unbounded executors (the three fixed pools are bounded and named), `parallelStream`, `Thread.sleep` in production code, `synchronized` on a request path other than the key-store miss above, static mutable state (one `HashMap` in `AuditActionCatalog`, filled once at class load), `findAll()` without a limit on a table that grows (the repository `List` methods that exist are scoped by an owner id: `findAllByOrganizationId`, `findAllByAccountId`, and the like; the question for each is how large an owner can grow, which the register's paging rows (TD-PERF-032 among them) answer for the lists that matter and which the next review should check for the rest).

## 3. Concurrency review

| Topic | State | Note |
|---|---|---|
| Scheduled jobs on more than one instance | Safe: each nightly sweep takes a session-level Postgres advisory lock through `PostgresAdvisoryJobLock`; the three jobs that do not (pending session closures, pending platform session closures, the Redis keyspace check) are queue-drains or checks that are idempotent | If TD-FUT-020 arrives, re-read the three: a drain run twice does the work once, which is the property that matters |
| Scheduler starvation | Pool of five (TD-PERF-003), graceful shutdown waits for a running job (TD-PROC-029) | A long nightly sweep and the two webhook ticks no longer share one thread |
| Races on counts and uniqueness | Closed where found (TD-ARCH-024 to 027, the team-per-role race, the registration cap) with advisory locks and database constraints | The pattern is consistent: a lock for a count, a unique index for a name. New aggregates should copy it, and the architecture tests are the place to say so |
| Optimistic locking | `@Version` appears in seven files | The register's rows say which aggregates are covered; a new aggregate that two operators can edit should have one |
| Outbound calls | Timeouts and circuit breakers on Resend, Google, GitHub and the webhook sender | One gap: the GitHub client's `HttpClient` has no connect timeout (TD-PERF-066) |
| Virtual threads | Measured and left off (TD-FUT-046) | Revisit only with the pool of ten solved first; virtual threads move the limit from threads to connections |
| Redis degradation | Start-up, suspension, deletion and erasure survive an outage (ADR-0045, TD-ARCH-062, TD-ARCH-063); counters fail open with a metric and an alert | The degraded modes now page (TD-PROC-036) |

## 4. Optimisation backlog

Ordered by value for effort. Each item says what to measure first. None is scheduled.

1. **Measure the cold start of the key store** (TD-PERF-066). One afternoon, a script that creates 500 Organizations and restarts. If the first-token latency per Organization stays under the target, close the row as measured and leave the monitor.
2. **AppCDS in the image build** (TD-PERF-064). Build the archive in the build stage with `-XX:ArchiveClassesAtExit`, copy it, start with `-XX:SharedArchiveFile`. Measure with `scripts/measure-startup.sh`; keep only if the gain is large enough to matter against a deploy window of 150 s.
3. **Split the test suite by module in the pipeline** (TD-PROC-012). The reactor already has six modules; four of them are small. Running the two large ones in parallel jobs costs runner minutes and saves wall time.
4. **Per-endpoint request budgets, not a global thread count**. The bulkhead is the model. The next candidates are the endpoints that call out (token exchange with a social provider, mail) where a slow dependency holds a Tomcat thread: a semaphore per dependency with a short wait, so a slow Resend cannot take fifty threads.
5. **Static assets**: the application serves its own JavaScript and CSS with compression on; a content hash in the file name plus `Cache-Control: immutable` removes the revalidation on every page. Check what `vendored-assets.json` and the existing cache headers already do before changing anything.
6. **Database**: the register shows the hot-path indexes are pinned by a test (`HotPathIndexesInt...`) and autovacuum is tuned for the seven churn tables (TD-PERF-065). Next, a `pg_stat_statements` extension and a weekly report of the ten slowest statements; there is no query-level view today, and every later database decision depends on one. Also the exporter TD-PERF-065 names as not done.
7. **Read replicas** only once item 6 shows a read-heavy statement set.

## 5. Improvements that are not optimisation

These come from the same review. The register's §7 has the ones that were already proposed; the new ones are listed in TD-SEC-105 to 108, TD-ARCH-064, TD-PROC-039 and TD-FUT-084 to 087.

- **Engineering**: guard scripts for the deployment (TD-TEST-022); a check that every `@SuppressWarnings` carries a comment; an ADR for webhook versioning (TD-ARCH-064); an ADR for the database roles (TD-SEC-106).
- **Operations**: restore drills on a schedule, not only a restore script; point-in-time recovery (TD-FUT-085); the monitoring stack shown running on the deployed host (TD-PROC-032, still open).
- **Developer experience**: a `make` or script target that starts the stack, seeds a demo Organization and prints the issuer URL, as the conformance seed scripts already do for the suite; a sample receiver for webhooks in two languages (TD-FUT-080).

## 6. What differentiates the product, and what protects it

A reviewer's honest list, from what is in the repository today and not from what is planned. Each is a thing most identity providers do not do, and each has a cost to keep.

| Point | Why it differentiates | What keeps it true |
|---|---|---|
| **Per-Organization issuers with per-Organization signing keys** | A compromise is bounded to one tenant's JWKS, and a relying party never sees another tenant's keys | The isolation tests; TD-FUT-087 and the BYOK row take it further |
| **A strict CSP that is tested, not asserted** | `script-src 'self'` with no inline script anywhere, proven by a browser gate under the real policy | The Playwright job; the check that no template reintroduces an inline handler |
| **Evidence in the repository** | Conformance logs (Config, Basic and RP-Initiated Logout plans, 0 failures), load results, mutation reports and the register itself live beside the code | `docs/05-engineering/conformance-results/`; the habit of archiving every run |
| **Security behaviour that is a policy per Organization** | Password-only, code-led, passkeys, TOTP, social providers, device trust, reuse windows and access lists are switches an Organization controls, each with an audit trail | The "every policy is enforced on every path" test (§7) |
| **A pipeline that is a control, not a ceremony** | SHA-pinned actions, a Trivy gate, a signed image, an SBOM, a licence review, a Maven retry that never retries a real failure (TD-PROC-038) | TD-PROC-039 closes the three things it still lacks |
| **Retention proven, not promised** | Every expiring table is swept under a lock with a test | The retention jobs' tests |
| **Honest degradation** | Redis loss and provider outages have a defined behaviour, a metric and an alert | ADR-0045, TD-PROC-036 |
| **Tenant audit log with export and streaming** | Often a paid feature elsewhere | The audit export tests; TD-SEC-106 would make it tamper-resistant against the application itself |
| **A webhook sender that pins the resolved address, refuses redirects and signs with a timestamp** | The SSRF class is closed at the connection, not only at registration | `PinnedAddresses`, ADR-0047; TD-ARCH-064 would add the contract version |

The weakest point to defend is the one the first customer will ask about: **what happens to my users' data if the host is lost** (TD-FUT-085, TD-FUT-029) and **what can a compromised container do** (TD-SEC-105, TD-SEC-106). Both are cheaper to answer before the first customer than after.

## 7. What this plan leaves out

- A capacity model. There is a measured limit for Argon2 on a three-vCPU machine and nothing for a larger one; a model would be a guess until a second host size is measured.
- A cost analysis. The single VM is the cost model; TD-FUT-020 and TD-FUT-021 are where it changes.
- Front-end performance beyond the notes above. The pages are server-rendered, the JavaScript is 28 small files and the transfer is compressed; a Lighthouse run on the five most-visited pages would say whether anything else is worth doing, and has not been done.
