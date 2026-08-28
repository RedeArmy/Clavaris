# Coding Standards — Clavaris

🟡 En revisión

## 1. Language and style

Java 25. Standard Java conventions (naming, package structure) as already fixed by the module layout (§7 of `domain-model.md` / `system-design-document.md`). No divergence from JobSeeker's own coding-standards baseline (`../../JobSeeker/docs/05-engineering/coding-standards.md`) except where this document says otherwise.

**Formatting and clean-code rules are a build gate, not a style guide (added 2026-08-17)** — enforced project-wide via the root `pom.xml`'s `<build><plugins>` (inherited by every module automatically, no per-module opt-in):

- **Formatting — Spotless, Google Java Format.** No house style to invent or bikeshed; the same zero-config style Google's own engineers write against. Bound to the `validate` phase, so `mvn verify` fails before compilation even starts if code isn't formatted. Fix locally with `mvn spotless:apply` before committing — never hand-format to satisfy it.
- **Clean-code rules — PMD**, ruleset at `pmd-ruleset.xml` (repo root): `bestpractices`, `errorprone`, `codestyle`, and `design` categories, minus `LoosePackageCoupling` (needs config this project has no use for — module boundaries are already enforced by ArchUnit, §2 below). Bound to `verify`, fails the build on any violation. Both gates live-verified against a deliberately injected violation (an empty catch block, an unformatted file) before being trusted, not assumed to work from the plugin config alone.
- **A rule that's a genuine false positive for a specific class** (e.g. PMD's `UseUtilityClass` against a `@SpringBootApplication` entry point — a private constructor there would break Spring's ability to instantiate it as a configuration bean) gets a targeted `@SuppressWarnings("PMD.RuleName")` with a comment explaining why, on that one class — never a blanket rule exclusion in `pmd-ruleset.xml` to silence a false positive that only applies in one place.

## 2. Hexagonal dependency rule enforcement

The hexagonal dependency rule (`domain/` depends on nothing; `application/` depends only on `domain/`; `infrastructure/` depends on both, never the reverse) is enforced by an **ArchUnit test running in CI**, not by code review alone — a violation fails the build. Lives at `app/src/test/java/com/clavaris/app/architecture/HexagonalArchitectureTest.java` (checks every business module's compiled classes at once, since `app` is the one module that depends on all of them); live-verified against a deliberately injected violation before this was trusted, not just assumed to work. See `test-strategy.md` §2.

## 3. Commenting standard

Every class or method encoding a business rule, architectural decision, or non-obvious technical choice gets a 1-3 line comment explaining *why*, referencing the `BR-XXX-NN` ID when one exists. No restating *what* the code does. No commented-out code. No TODO without an owner and a reason.

Given this system's security-critical nature, this standard is applied with particular strictness to:
- Anything touching password/token hashing configuration (reference ADR-0005/ADR-0002).
- Anything touching refresh token rotation or reuse detection (reference BR-ID-03).
- Anything touching `redirect_uris` matching (reference BR-CLIENT-01).

## 4. Security-specific conventions

- Never log a credential, token, or password hash — not even at `DEBUG` level, not even temporarily during development (BR-DATA-01). If a debugging session needs to inspect a value like this, do it in a debugger, not a log statement.
- Never construct a SQL query by string concatenation — JPA/parameterized queries only, no exceptions for "trusted" internal input.
- Every new endpoint on the management API surface must have an explicit authorization check reviewed against the roles table in `domain-model.md` §3 — no endpoint ships relying on "nothing calls this without a token" as its only protection.

## 5. When cross-module duplication moves into `common` (TD-ARCH-008)

CLAUDE.md §4 scopes `common` to "shared code only (base exceptions, utilities, common value types) — no business logic." Two real cross-module duplication findings have each been resolved differently, and until now neither precedent explained *why* — this is that explanation, written before a third instance gets decided ad hoc.

- **TD-ARCH-001 (a deliberate per-module mirror, not shared)**: two modules independently implement the same-shaped rate-limiting/audit logic. Left as a documented, intentional duplicate — each module's own version can diverge the day one module's own domain needs something the other doesn't (a different retention window, a different audit-event shape), without that divergence being a breaking change to a shared type both modules depend on.
- **TD-ARCH-007 (extracted into `common`)**: `AbstractEventOutboxEntity` and `EventOutboxRetentionSweeper` — identity-module's and organization-module's own event-outbox retention sweep were byte-for-byte the same logic (age-based cutoff, same delete query shape, same log line), flagged as SonarCloud duplication. Extracted, not mirrored.

**The actual rule, stated once so it doesn't have to be re-derived per instance**: share via `common` when the duplication is pure structural/persistence boilerplate with zero domain meaning of its own — the two copies would only ever change in lockstep, and a future divergence between them would be a bug, not a legitimate design choice. Keep the deliberate per-module mirror when the duplicated code encodes anything a future domain-specific change might plausibly need to diverge on — retention policy, audit semantics, business timing — even if the two copies happen to read identically today. `EventOutboxRetentionSweeper`'s own age-based cutoff is genuinely the same operational decision in both modules (neither has any webhook dispatcher yet, per its own Javadoc) — that sameness is *why* extraction was correct there, not a coincidence the rule ignores. If a future divergence is even plausible, mirror; if the two copies converging is the only outcome that would ever make sense, share.

A `common` addition under this rule still needs the same one-line-in-the-class comment discipline (§3 above) explaining which side of this rule justified it — not just that it removed a Sonar finding.

## 6. Relevant ADR range

ADRs 0001-0006 (`docs/03-architecture/adr/`) — all currently locked, considered settled unless a new ADR explicitly revisits one of them.
