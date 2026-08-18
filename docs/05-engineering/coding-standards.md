# Coding Standards — Clavaris

🟡 En revisión

## 1. Language and style

Java 21. Standard Java conventions (naming, package structure) as already fixed by the module layout in `CLAUDE.md` §7.1. No divergence from JobSeeker's own coding-standards baseline (`../../JobSeeker/docs/05-engineering/coding-standards.md`) except where this document says otherwise.

## 2. Hexagonal dependency rule enforcement

The rule in `CLAUDE.md` §7.2 (`domain/` depends on nothing; `application/` depends only on `domain/`; `infrastructure/` depends on both, never the reverse) is enforced by an **ArchUnit test running in CI**, not by code review alone — a violation fails the build. Lives at `app/src/test/java/com/clavaris/app/architecture/HexagonalArchitectureTest.java` (checks every business module's compiled classes at once, since `app` is the one module that depends on all of them); live-verified against a deliberately injected violation before this was trusted, not just assumed to work. See `test-strategy.md` §2.

## 3. Commenting standard

Same as `CLAUDE.md` §8: every class or method encoding a business rule, architectural decision, or non-obvious technical choice gets a 1-3 line comment explaining *why*, referencing the `BR-XXX-NN` ID when one exists. No restating *what* the code does. No commented-out code. No TODO without an owner and a reason.

Given this system's security-critical nature, this standard is applied with particular strictness to:
- Anything touching password/token hashing configuration (reference ADR-0005/ADR-0002).
- Anything touching refresh token rotation or reuse detection (reference BR-ID-03).
- Anything touching `redirect_uris` matching (reference BR-CLIENT-01).

## 4. Security-specific conventions

- Never log a credential, token, or password hash — not even at `DEBUG` level, not even temporarily during development (BR-DATA-01). If a debugging session needs to inspect a value like this, do it in a debugger, not a log statement.
- Never construct a SQL query by string concatenation — JPA/parameterized queries only, no exceptions for "trusted" internal input.
- Every new endpoint on the management API surface must have an explicit authorization check reviewed against the roles table in `domain-model.md` §3 — no endpoint ships relying on "nothing calls this without a token" as its only protection.

## 5. Relevant ADR range

ADRs 0001-0006 (`docs/03-architecture/adr/`) — all currently locked, referenced from `CLAUDE.md` §10.
