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
java -jar app/target/clavaris-app.jar &        # or docker compose up -d app
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

## Files

- `run.sh` — the exact `ab` invocations behind every number above, runnable against any instance.
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
