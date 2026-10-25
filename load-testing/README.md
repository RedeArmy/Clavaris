# Load / capacity testing — Clavaris

TD-TEST-004. First real, measured answer to "what is this system's actual breaking point" —
`nfr-quality-attributes.md` §3 committed to a p95 < 300ms target for `/oauth2/token` without ever
having run a single request against it under concurrent load. This directory holds the script used
to reproduce the numbers and the raw output captured the one time it was actually run
(2026-08-24), kept as `results/*.txt` — not deleted, so the next run has a real baseline to diff
against instead of starting from nothing.

## Tooling choice

Apache Bench (`ab`) — already present on this machine, no new dependency to install or vet. It
can't drive the full interactive Authorization Code + PKCE flow (each authorization code is
single-use and requires a real browser-shaped session), so this pass deliberately scopes to what
`ab` *can* legitimately exercise end-to-end against a real running instance:

1. **`GET /oauth2/jwks`** (platform tier) — cheap, unauthenticated, not rate-limited. Baseline for
   raw HTTP/Tomcat/JVM capacity on this stack, uncomplicated by password hashing.
2. **`POST /oauth2/token`** (platform tier, `client_credentials`) — the actual endpoint the NFR
   target names. Confidential clients only (ADR-0013), so this goes through real Argon2id secret
   verification per request, and real anti-abuse rate limiting (ADR-0010 §6.1) per request too.

**Known, explicit gap this pass does not close**: the per-`Organization` interactive login +
token-exchange path (the one real end users actually take) isn't load-tested here — it needs a
client that can complete a real PKCE exchange per iteration, not a static POST body `ab` can
replay. `ab`'s own numbers below are a legitimate proxy for the OAuth2/Argon2/Postgres/Redis layer
shared by both tiers, not a substitute for testing that specific path. Worth a dedicated harness
(e.g. a small Java/k6 script that logs in and completes PKCE per iteration) the next time this is
revisited — tracked, not silently assumed away.

## How to reproduce

```bash
cp .env.example .env   # fill in real values — see .env.example's own comments
docker compose up -d postgres redis
mvn -pl app -am package -DskipTests
SPRING_PROFILES_ACTIVE=dev java -jar app/target/clavaris-app.jar &        # or docker compose up -d app
./load-testing/run.sh                          # see script — runs every scenario below in order
```

`run.sh` needs a `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` pair seeded (`.env`, same as any other
run) and reads them from the environment, the same way the app itself does.

## What was actually measured, and what it found (2026-08-24)

Run against a real `docker compose` Postgres 16 + Redis 7 stack and a real `java -jar` process, on
this development machine — **3 CPU cores, shared with the `ab` client process itself**. Not
production hardware, not a dedicated load generator on a separate host. Every number below is a
floor on what a real multi-core production host would do, not a ceiling — but the *shape* of the
finding (Argon2id concurrency, not Postgres/Redis, is the near-term bottleneck) is real and
transfers directly.

### 1. Baseline capacity (`/oauth2/jwks`, no Argon2, no rate limit)

| Concurrency | p50 | p95 | p99 | req/s | Failures |
|---|---|---|---|---|---|
| 50 | 21ms | 56ms | 66ms | 1845/s | 0 |
| 100 | 77ms | 225ms | 349ms | 1051/s | 0 |
| 300 | 72ms | 1068ms | 1279ms | 1946/s | 0 |

Zero failures at every concurrency level tested, up to 300 simultaneous connections — the raw
HTTP/JVM/Tomcat stack itself has real headroom well past what a single-digit-consumer v1 deployment
(`nfr-quality-attributes.md` §3's own stated expected load) will ever produce. The c=300 p95 spike
is this 3-core box's own connection-handling saturation (`ab` and the server contending for the
same cores), not a Clavaris-specific ceiling — flagged here rather than presented as a hard number.

### 2. `/oauth2/token` under real concurrent load — the actual finding

**Argon2id, not Postgres or Redis, is this endpoint's real bottleneck**, and it's a much lower
ceiling than the baseline above:

| Concurrency | p50 | p95 | p99 | vs. 300ms NFR target |
|---|---|---|---|---|
| 1 (sequential) | 53ms | 161ms | 185ms | ✅ comfortable |
| 3 | 116ms | **316ms** | 395ms | ⚠️ at the boundary |
| 10 | 317ms | **537ms** | 636ms | ❌ fails |
| 30 | 1050ms | **14,193ms** | 14,926ms | ❌ fails badly (queueing collapse) |

(The rate-limit config was temporarily raised for this one experiment only —
`clavaris.rate-limit.token.per-client-limit` — specifically to isolate Argon2/Postgres capacity
from rate-limiter enforcement; reverted immediately after, see §3 below for the limiter's own
behavior at its real default.)

Argon2id is deliberately CPU/memory-hard (ADR-0005) — that's the point of choosing it over BCrypt.
The direct consequence, now measured rather than assumed, is that concurrent `client_credentials`/
password verifications compete for the same limited CPU budget, and request latency degrades
sharply, not gracefully, once concurrent verifications exceed roughly the available core count.
This is real load-bearing information for capacity planning: **the p95 < 300ms target is only
credible up to roughly single-digit concurrent authentication requests on hardware this modest.**
A production host with materially more cores pushes this ceiling up proportionally (Argon2
verifications parallelize across cores, they don't serialize), but the *mechanism* — CPU-bound
password hashing is the actual limiting resource for `/oauth2/token`, not the database or Redis —
is real and doesn't change with more cores, only the specific number where it bites does.

**Recorded as new debt, not silently left as a one-off finding**: `technical-debt-register.md`
TD-FUT-017.

### 3. Rate limiter under real concurrent load (real default config, real Redis)

50 simultaneous `client_credentials` requests against one `PlatformClient` (default
`per-client-limit: 20` per 5-minute window, ADR-0010 §6.1): **18 admitted (200), 32 correctly
rejected (429), zero 500s, zero exceptions in the application log.** This is the concurrency-level
proof TD-TEST-003 didn't have — that specific row only ever proved the filter is wired onto the
right chain via sequential unit tests; this is the same rule holding correctly under genuine
concurrent contention against real Redis, not a mocked `RateLimiter`.

### 4. Argon2 parameter comparison (2026-08-28) — real data for TD-FUT-017's own open "options to evaluate"

TD-FUT-017's own text named "tuning Argon2's own cost parameters within OWASP's recommended
range" as an unevaluated option. This run evaluates it for real, against real concurrent Argon2id
verification (`Argon2ConcurrencyBenchmark.java`, this directory) — isolating the exact mechanism
(Argon2 CPU/memory contention, not Tomcat/Postgres/Redis, already shown above to have headroom) by
calling `Argon2PasswordEncoder.matches` directly, at the same concurrency levels (1/3/10/30) as §2
above, on the same 3-core machine.

**Confirmed live, not assumed, before comparing anything**: decoded a real hash produced by
`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` against this project's own resolved
`spring-security-crypto:7.1.1` — the actual current parameters are `m=16384` (16 MiB), `t=2`,
`p=1`, not a number taken from documentation.

| Concurrency | CURRENT p95 (m=16384,t=2,p=1) | CURRENT throughput | LOWER_MEMORY p95 (m=9216,t=4,p=1) | LOWER_MEMORY throughput |
|---|---|---|---|---|
| 1 | 65.7ms | 19.6/s | 69.8ms | 20.0/s |
| 3 | 129.3ms | 32.0/s | **112.4ms** | **47.2/s** |
| 10 | 597.7ms | 32.0/s | 552.3ms | **39.0/s** |
| 30 | 1117.5ms | 26.6/s | **1620.1ms** | 31.1/s |

**Honest reading, not a clean win either way**:

- At **moderate concurrency (c=3, c=10)** — the realistic near-term range this row's own "roughly
  single-digit concurrency" framing names — the lower-memory alternative is a real, measured
  improvement: +47% throughput at c=3, both p95 figures modestly better. Smaller per-verification
  memory footprint does appear to reduce contention in this range, on this hardware.
- At **severe oversubscription (c=30, already a "queueing collapse" regime for both variants)**,
  the lower-memory alternative is measurably *worse* on p95 (1620ms vs. 1118ms) — more iterations
  (`t=4` vs. `t=2`) means more total queued CPU-bound work once every core is already saturated
  many times over.
- **Neither variant moves where the ceiling actually sits**: both still fail the 300ms p95 NFR
  target at c=10. This benchmark measures pure in-process Argon2 cost only (no Tomcat/Postgres
  round trip), so its own numbers are systematically lower than §2's real HTTP-level ones — the
  *shape* of the finding (a hard ceiling exists, tuning shifts it, doesn't remove it) is what
  transfers, not a claim that either parameter set alone clears the NFR target.

**What this does and does not change**: TD-FUT-017 stays open, correctly — its blocking dependency
(TD-FUT-013, no production host to make a real tuning decision against) is unrelated to this data
and still doesn't exist, and the row's own trigger condition (real concurrent traffic actually
occurring) still hasn't happened. What changes is that "tune Argon2 parameters" is no longer a
purely theoretical option in that row's own text — it's now a real, measured trade-off (helps at
c=3–10, hurts at c=30) on record for whoever makes that call once a production host exists.
**Production parameters were deliberately left unchanged** — `Argon2PasswordHasher`/
`Argon2PasswordVerifier` still use `defaultsForSpringSecurity_v5_8()`; changing a live password
hash's own cost parameters is a real security/performance decision, not something to flip as a
side effect of gathering evidence for it.

## 5. The interactive sign-in flow, and what hashing generated secrets changed (2026-10-24)

Everything above measured one thing: `client_credentials` against the platform tier, with a secret
an operator chose. Two gaps in that: it never exercised the flow people actually use (authorize,
sign in with a password, come back with a code, exchange it with the client secret and the PKCE
verifier), and the secrets real consuming applications hold are generated by the server, not chosen.
This section adds the first and measures the second.

**Setup and tools**, all in this directory, no build needed:

```bash
# A throwaway instance with the rate limits raised, so the limiter is not what gets measured
# (CLAVARIS_RATE_LIMIT_{TOKEN_PER_CLIENT,TOKEN_REFRESH_PER_CLIENT,LOGIN_PER_ACCOUNT,LOGIN_PER_IP}_LIMIT
# and CLAVARIS_RATE_LIMIT_CAPACITY_{DEFAULT,HARD_CAP,DEVELOPMENT_DEFAULT}_REQUESTS_PER_MINUTE, all
# very large), SPRING_PROFILES_ACTIVE=dev, PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET set.
./load-testing/setup-pkce-fixture.sh > fixture.env     # Organization, 2 clients, 30 accounts
set -a; . ./fixture.env; set +a
./load-testing/run-token-scenarios.sh                  # /oauth2/token, ab, markdown table
java load-testing/PkceLoginLoadTest.java               # the whole sign-in flow, per-stage latency
```

The accounts use an address with the `+clavaris_test` marker, so no real email is ever attempted.
Flush the instance's Redis between runs if you also run with default limits: the limiter's counters
live there and survive a restart of the app.

**Conditions, stated because they bound every number.** One run per row, on the same 3-core,
9 GB development machine as §1–§4, with the load generator on those same cores. Postgres and
Redis in containers on that machine too. A first pass of 20–30 discarded warm-up requests per
scenario. Not repeated, so small differences (a few ms, a few points of throughput) are noise;
the large ones below are not. The 2026-08-24 table in §2 predates the Argon2 bulkhead, so the
"before" column below is a fresh run of the code as it stood, not a reuse of that table.

### 5a. `/oauth2/token` with a server-generated client secret

Same instance, same machine; the only change is how the client secret is stored and checked
(ADR-0005 addendum: SHA-256 for a 256-bit generated secret, Argon2id kept for secrets a person
chooses). 400 requests per row.

| Concurrency | Before: p50 / p95 / failures | After: p50 / p95 / failures | req/s before → after |
|---|---|---|---|
| 1 | 75 / 134 ms / 0 | 22 / 42 ms / 0 | 12 → 41 |
| 3 | 87 / 176 ms / 0 | 31 / 58 ms / 0 | 30 → 86 |
| 10 | 317 / 505 ms / **8** | 70 / 143 ms / 0 | 29 → 126 |
| 30 | 385 / 677 ms / **272 of 400** | 158 / **271 ms** / 0 | 69 → 175 |

Before, the "failures" were the Argon2 bulkhead rejecting legitimate clients (`server_error`), which
is also why the c=30 latency looks moderate: two thirds of the requests were refused quickly. After,
none are, and p95 stays under the 300 ms target (`nfr-quality-attributes.md` §3) up to 30
concurrent requests on three shared cores.

Controls, same run, to show the difference is the hashing and nothing else:

| Client secret | Concurrency | p95 / failures |
|---|---|---|
| Platform bootstrap (chosen by the operator, Argon2id) | 3 / 10 / 30, before | 231 ms / 552 ms (20 failed) / 547 ms (266 failed) |
| Platform bootstrap, after the change | 3 / 10 / 30 | 206 ms / 429 ms (3 failed) / 512 ms (237 failed) |
| A tenant client registered *before* the change (Argon2id hash), on the new build | 1 / 3 / 10 | 65 ms / 145 ms / 390 ms (5 failed) |

The Argon2 rows are unchanged within noise, as they should be: an existing client keeps working
and keeps its old cost until its secret is rotated. A new deployment should rotate the bootstrap
secret once after first start (`deployment-runbook.md` §3), which moves it to the fast scheme.

### 5b. The whole interactive flow (authorize → sign in → code → token)

Each flow is a fresh browser: 30 accounts, a distinct one per flow, no consent screen. "Sign-in"
is the password POST; "token" is the code exchange. Failures are named by stage and status.

| Concurrency | Flows | Before: completed / flow p95 / sign-in p95 / token p95 | After: completed / flow p95 / sign-in p95 / token p95 | flows/s before → after |
|---|---|---|---|---|
| 1 | 30 | 30 / 677 / 183 / 225 ms | 30 / 667 / 242 / 102 ms | 2.7 → 3.2 |
| 3 | 30 | 30 / 934 / 332 / 238 ms | 30 / 879 / 557 / 159 ms | 4.8 → 5.8 |
| 10 | 100 | 98 / 2226 / 890 / 902 ms | **100** / 1393 / 656 / 401 ms | 5.6 → 8.7 |
| 30 | 300 | **84** / 4091 / 1470 / 1709 ms | **240** / 3411 / 1308 / 1200 ms | 3.8 → 9.3 |

At 30 simultaneous sign-ins the "before" run completed 84 of 300 flows (177 sign-ins refused with
`503`, 39 code exchanges with `400`); "after" completes 240 and the only refusals left are 60
sign-ins (`503`).

**What this says, and what it does not.**

- The code exchange stopped being the problem. What is left is the password POST: one Argon2id
  verification of a *human-chosen* secret, which is exactly what Argon2id is for (ADR-0005) and is
  not made cheaper here. At 10 concurrent sign-ins on this machine a whole flow takes ~1.4 s at
  p95; at 30 the admission control (permits = CPU cores, 250 ms maximum wait,
  `clavaris.security.argon2.*`) sheds about one in five sign-ins by design instead of letting the
  host collapse. Those two settings trade refusals for latency; they were not changed, because the
  right values depend on a production host this repository does not have yet (TD-FUT-017).
- Sustained interactive capacity on this machine is roughly 9 sign-ins/s, with the generator
  competing for the same cores. A dedicated, larger host raises that roughly in proportion to its
  cores; this has not been measured.
- This measures one Organization, one client and 30 accounts on one JVM and one database. It says
  nothing about many tenants, a populated database, or Redis under real session counts.

### 5c. Found while running it: a consuming application can complete 20 sign-ins every 5 minutes

With the limits at their defaults (which §5a/§5b deliberately raise), a run of 30 sign-ins for one
client, on one IP, one account each, after clearing the limiter's counters:

```
| 1 | 30 | 20 | ... | {token:429=10} |
```

Exactly 20 complete. The other ten sign in correctly and are then refused at the code exchange,
because the `token:client` rule allows 20 non-refresh requests per OAuth client per 5 minutes on
`/o/*/oauth2/token`, and an `authorization_code` exchange is one. That is the whole client, across
all of its users: about four sign-ins a minute. The rule exists for the secret-guessing half of the
endpoint, but it counts every request, including successful exchanges of a single-use,
PKCE-bound code that no one is guessing. Raising it is a security-policy decision (it is a fixed
anti-abuse limit, ADR-0010 §6.1), so it is recorded as a follow-up and not changed here.

## Files

- `run.sh` — the exact `ab` invocations behind §1–§3, runnable against any instance. Its §2 uses the
  platform bootstrap client, whose operator-chosen secret keeps Argon2id; for the credentials a
  consuming application actually holds, use `run-token-scenarios.sh`.
- `setup-pkce-fixture.sh`, `run-token-scenarios.sh`, `PkceLoginLoadTest.java` — §5.
- `results/*.txt` — raw `ab` output from the 2026-08-24 run, kept as the historical baseline.
- `Argon2ConcurrencyBenchmark.java` — the standalone Argon2-only concurrency benchmark behind §4
  above. Compile/run against the exact classpath this project resolves (no Maven plugin
  configured for this — it's a deliberately standalone tool, not part of the build):
  ```bash
  cd load-testing
  CP=$(find ~/.m2 -name "spring-security-crypto-7.1.1.jar" | grep -v sources):\
$(find ~/.m2 -name "bcprov-jdk18on-*.jar" | grep -v sources | head -1):\
$(find ~/.m2 -name "commons-logging-*.jar" | grep -v sources | head -1):\
$(find ~/.m2 -name "spring-core-7.0.9.jar" | grep -v sources)
  javac -cp "$CP" Argon2ConcurrencyBenchmark.java
  java -cp "$CP:." Argon2ConcurrencyBenchmark
  ```
  Re-check the resolved `spring-security-crypto`/`spring-core` versions (`mvn -pl identity-module
  dependency:tree`) before reusing this command verbatim — they will drift over time.
