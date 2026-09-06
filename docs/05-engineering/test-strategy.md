# Test Strategy — Clavaris

🟡 En revisión

## 1. Why this system's testing bar is higher than a typical feature module

Clavaris is the credential store and token issuer for every consuming application. A regression here isn't "one feature breaks" — it's "every consumer's login breaks, or worse, a security invariant silently stops holding." Test coverage on `identity-module` and `client-registry-module` should be treated with the same seriousness as JobSeeker treats its compatibility-scoring engine.

## 2. Test pyramid

| Level | Focus | Tooling (expected, to be confirmed at implementation time) |
|---|---|---|
| Unit | Domain logic in isolation — password policy validation, refresh token rotation-chain logic, workspace membership role invariants (BR-WS-01) | JUnit 5, no Spring context |
| Integration | Persistence adapters against a real Postgres (Testcontainers) — every repository implementation, migration correctness | JUnit 5 + Testcontainers |
| Contract / conformance | The OIDC surface itself — Authorization Code + PKCE flow end-to-end, JWKS correctness, discovery document completeness | Spring Boot `@SpringBootTest` against a running Authorization Server context; OpenID Foundation conformance suite ahead of v1 exit (`vision-document.md` §2) |
| Security-specific | Refresh token reuse-detection actually revokes the full token family; rate limiting actually triggers at the configured threshold; Argon2id parameters actually resist a quick brute-force check | Dedicated test suite, not folded into generic unit tests — these are the tests that most directly validate `threat-model-stride.md` mitigations |
| Architecture | Hexagonal dependency rule enforcement (`domain/` importing nothing from `org.springframework.*` or `jakarta.persistence.*`) | ArchUnit, run in CI on every build — not a manual review step. `HexagonalArchitectureTest` (`app` module) — live, not aspirational, as of this commit |
| Client-side JS | ADR-0009 §1's `embedded-login-popup.js` — the one piece of this project's own frontend outside Thymeleaf's server-rendered templates | Node's own built-in `node:test`, run in CI on every build (`app/package.json`) — deliberately zero npm dependencies (no jsdom/Jest); hand-rolled `document`/`window` stubs are enough for one small vanilla-JS file. Revisit if this repo's client-side JS footprint ever grows past "a handful of small scripts." |

## 3. What must never ship without a test

- Any change to refresh token rotation or reuse-detection logic (BR-ID-03) — this is the single highest-value security invariant in the system.
- Any change to `redirect_uris` matching logic (BR-CLIENT-01) — open-redirect-class bugs here are directly exploitable.
- Any change to the account-deletion cascade (BR-DATA-03) — an incomplete cascade leaves orphaned access.
- Any change to password/token hashing configuration (ADR-0005) — a silently-weakened hash parameter is invisible without a test asserting the configured cost factor.
- Any migration that **alters an existing table with existing rows** (rename, type change, split/merge column, drop with data implications) — a migration that only ever runs against an empty schema (every migration test elsewhere by default) proves nothing about data safety. Required pattern: apply schema up to the prior version, seed representative data, apply the migration under test, assert the data is intact and correctly transformed — not just that the migration runs without an error. Worked example, live-verified against both a broken and a correct version of the same migration: `app/src/test/java/com/clavaris/app/migration/MigrationDataPreservationTest.java` (`data-model.md` §4). A migration that only ever *adds* new, empty structures (a new table, a nullable column) does not need this — there is no existing data to lose.

## 4. Conformance testing

Before the v1 exit criterion (`roadmap-and-release-plan.md` §2) is considered met, the deployed instance must pass the OpenID Foundation's conformance test suite for the Authorization Code flow with PKCE — an external, objective bar rather than "we believe this is OIDC-compliant."

## 5. What's deliberately not tested exhaustively in v1

Load/performance testing beyond the generous targets in `nfr-quality-attributes.md` §3 — not meaningful at this project's expected v1 traffic (single-digit consumers). Revisit once real usage data exists, consistent with the same "don't over-engineer for a load level we don't have" stance taken in the NFR document itself.
