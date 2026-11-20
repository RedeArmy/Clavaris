# ADR-0050: A licence policy checked in CI, a notice file, and Redis kept over Valkey for now

**Status:** 🟡 Pending review (2026-11-20)

Closes TD-FUT-082. The same pass closed TD-TEST-020 and TD-PROC-037, which are build-and-measurement fixes rather than decisions; their resolutions are in the register (§6) and the comments of the files they changed.

## Context

The long-term intent recorded in the vision is to offer Clavaris to other organisations, in a self-hosted and a managed shape. Before that, a customer's legal team will ask what every component is licensed under, and until now the answer would have been assembled by hand: no workflow checked a licence, the SBOM the release already generates (CycloneDX) was never read for them, and `docker-compose.yml` pins `redis:8`, whose licensing changed twice in two years. The repository's own `LICENSE` is proprietary and unchanged by this ADR.

Nothing here is legal advice. No counsel has reviewed it, and one should before the service is offered to a third party (see "What this does not cover").

## 1. Redis stays, Valkey is the recorded alternative, and it is not yet a verified drop-in

### What the licences are (read on 2026-11-20 from redis.io/legal/licenses and the Linux Foundation's announcement of Valkey)

| Software | Licence |
|---|---|
| Redis 7.2 and earlier | BSD-3-Clause |
| Redis 7.4 to 7.8 | RSALv2 or SSPLv1 (source-available, not OSI open source) |
| Redis 8 (the pinned `redis:8`) | RSALv2, SSPLv1 or AGPLv3, at the user's choice; AGPLv3 is OSI-approved |
| Valkey (Linux Foundation, forked from Redis 7.2.4) | BSD-3-Clause |

### Decision

Keep Redis 8 for v1, run **unmodified, as a separate service, and not redistributed by us**: `docker-compose` pulls Redis from its own registry and our image does not contain it. On those facts the AGPLv3 option is the one in use, and its source-offer duties attach to a modified or redistributed Redis, neither of which is the case. The decision flips to Valkey if any of these becomes true:

1. a prospective customer's legal team rejects AGPLv3 and SSPLv1 for a component the service depends on;
2. Clavaris starts to ship Redis inside something it distributes (an image or a bundle), which is redistribution;
3. Redis changes its licence terms again.

### Why not switch now

A switch is two lines of image name, and BSD-3 is the simpler thing to quote. It is not done because **Valkey is not yet shown to be a drop-in for what this system relies on**. Evidence, from one run on 2026-11-20 with `valkey/valkey:8` (8.1.12) in place of `redis:7` in the Redis-backed tests:

- 77 tests in 12 classes: the Spring Session and distributed-session tests, refresh-token rotation, code lockout, the token-endpoint rate limit, the keyspace-events check (`CONFIG GET` and `CONFIG SET` of `notify-keyspace-events`) and the operator-suspension test with the Redis container paused. All passed except one.
- **`CountersRedisSeparationTest.theSameFloodOnTheInstanceThatAlsoHoldsSessionsMakesEveryWriteFail` timed out at its 10-minute limit.** Run alone it passed in 499 s; on `redis:7` it takes 32 s. A manual reproduction with the real reservation script showed both servers enforce `maxmemory` and return the same `OOM command not allowed` error (first refusal after about 25,000 reservations on Redis 7.4.12 and about 33,000 on Valkey 8.1.12), so it is not a missing behaviour. Why the same flood is about sixteen times slower through the application on Valkey was not found. That test is the evidence for the memory ceiling ADR-0045 and TD-SEC-092 rely on, so the gap is recorded as TD-FUT-083 and is the first thing to settle before choosing Valkey.

One more thing found on the way and not fixed here: the Redis-backed tests use `redis:7`, while `docker-compose.yml` and `docker-compose.prod.yml` pin `redis:8`. The suite has never run against the version that runs in production (TD-TEST-021).

## 2. A licence policy over the SBOM, checked on every pull request

`scripts/check-licences.py` reads the CycloneDX SBOM of the image the CI job just built and applies `infra/licences/licence-policy.json`.

- **Classes decided by the script from the licence text**: permissive, weak copyleft (LGPL, MPL, EPL, CDDL, and GPL with the classpath exception), strong copyleft (GPL), network copyleft (AGPL), source-available (SSPL, RSAL, BUSL, Commons Clause), non-commercial, and unknown. `A OR B` takes the lesser risk because we may choose; `A AND B` the greater.
- **What fails.** For Java libraries and bundled code: strong copyleft, network copyleft, source-available, non-commercial, and **no licence at all**. For operating-system packages of the base image: network copyleft, source-available and non-commercial only. Ordinary GPL in the OS layer is the Ubuntu base's own programs, aggregated by the image rather than linked.
- **What a person decides, in the policy file**: those two lists, which components are Clavaris's own code (`pkg:maven/com.clavaris/`), the licences an artefact's metadata omits (each with where it was read and a date), and reviewed exceptions (a reason and a date, or the file is refused).
- **Calibrated on the real image.** 352 components, 225 of them Java libraries or bundled code: 219 permissive, 6 weak copyleft (AspectJ weaver under EPL-2.0; the two Jakarta APIs under EPL-2.0 or GPLv2 with the classpath exception; Logback under EPL-2.0 or LGPL-2.1; and the JDK's `jrt-fs`), and none strong, network, source-available or non-commercial. Eight libraries had a licence the SBOM could not read (a URL or a file hash in its place); each is recorded with the pom that declares it.
- **Where it runs**: the `docker-build` job, on every pull request and not only on a push to `master`, from the image it built; and `scripts/test_check_licences.py` (13 tests) runs beside it.

The calibration used a recent `anchore/syft`; the action in CI pins its own, so the first run on a pull request is the real test of whether the two read licences the same way.

## 3. A notice file that cannot go stale unnoticed

`THIRD-PARTY-NOTICES.md` lists every Java library in the image with its licence and class, and the services that run beside it (PostgreSQL, Redis and Caddy). It is generated by `scripts/check-licences.py notices`, and `covered` fails CI when a library in the SBOM is not named in it, so a new dependency cannot ship without appearing there; a version bump does not fail, so versions are as of the last regeneration.

## What this does not cover

- **Legal review.** The policy decides which classes fail; it does not say what AGPLv3 or SSPLv1 mean for a particular commercial arrangement. That needs counsel before the service is offered to anyone else.
- **Operating-system packages** are classified and counted but only network copyleft, source-available and non-commercial terms fail; their individual notices are in the image's SBOM, not in the notice file.
- **Vendored front-end assets** are recorded with their licences in `vendored-assets.json` (htmx 0BSD, the Geist fonts under the SIL OFL) and have their own check.
- **Development-only dependencies** (the Playwright suite's npm packages) do not ship in the image and are not in the SBOM.
- **The PostgreSQL and Caddy images** are named in the notice file but not scanned by the policy.

## Consequences

- A dependency under a licence nobody agreed to is refused at review time, not found by a customer.
- A change of the pinned Redis licence is now a recorded decision with triggers, not something to notice.
- TD-FUT-083 (Valkey equivalence) and TD-TEST-021 (tests on a different Redis than production) are open and small.
