# Definition of Done — Clavaris

🟡 En revisión

A change is done when **all** of the following hold — not merely "compiles and passes the tests I thought to write":

## 1. Every change

- [ ] Tests added/updated per `test-strategy.md`, including the ArchUnit hexagonal-dependency check passing.
- [ ] `mvn spotless:apply` run before committing — `mvn verify` fails on unformatted code, not just flags it (`coding-standards.md` §1).
- [ ] No new PMD violation (`mvn verify`, `pmd-ruleset.xml`) — a genuine false positive gets a targeted `@SuppressWarnings` with a comment, never a ruleset-wide exclusion to silence it.
- [ ] Comment added/updated per `coding-standards.md` §3 if the change encodes a business rule or architectural decision.
- [ ] No PII, credential, or token value introduced into logs (BR-DATA-01) — checked explicitly, not assumed.
- [ ] CI green: tests, dependency vulnerability scan, ArchUnit, Spotless, PMD.
- [ ] PR reviewed (even solo — see `git-workflow.md` §4) before merge to `master`, and the SonarCloud Quality Gate (`git-workflow.md` §4a) is green.

## 1a. Every change to a *documented decision* (new/amended ADR, renamed or redefined domain concept)

Added after ADR-0010 shipped with five other documents left contradicting it (`security-architecture.md`, `system-design-document.md`, `api-contract-overview.md`, `integration-design.md`, `first-vertical-slice-blueprint.md`) — this checklist item exists specifically because that class of bug is cheap to catch here and expensive to discover later:

- [ ] `grep` the repo for the old term/shape being replaced (e.g. an entity rename, a changed endpoint path, a resolved "open question") — every hit either updated or confirmed intentionally unrelated, not left stale by omission.
- [ ] Every document that references the changed ADR/concept by name or number is re-read, not just the primary domain/data-model doc.
- [ ] `./scripts/check-doc-consistency.sh` passes locally (also runs in CI, `git-workflow.md` §4) — catches the specific regressions already found once; add a new check to that script in the same PR if this change introduces another rename/redefinition class it doesn't yet cover.

## 1b. Every change touching database schema (a new or altered migration)

- [ ] New migration filename follows the timestamp convention (`data-model.md` §4) — never a sequential `V{n}` that could collide with another module's migration.
- [ ] If the migration **alters a table that can already hold rows** (rename, type change, split/merge, a drop with data implications): a `MigrationDataPreservationTest`-style test exists — seed data on the prior schema version, apply the migration, assert the data survived intact (`test-strategy.md` §3). A migration that only adds new, empty structures doesn't need this.
- [ ] `spring.jpa.hibernate.ddl-auto` stays `validate` — never changed to `update`/`create` to "make it work locally."

## 2. Changes touching authentication, tokens, or sessions (identity-module)

- [ ] Corresponding row in `threat-model-stride.md` re-checked — does this change affect an existing mitigation, or introduce a new threat not yet modeled?
- [ ] If the change touches refresh token rotation, reuse detection, or password/token hashing: a dedicated security-specific test exists (`test-strategy.md` §3), not just a happy-path unit test.

## 3. Changes touching the OIDC/OAuth2 surface (client-registry-module)

- [ ] Change verified against the relevant OIDC/OAuth2 spec section, not just "consumer app worked in manual testing."
- [ ] `redirect_uris` matching logic, if touched, re-verified to still be exact-match only (BR-CLIENT-01).

## 4. Before v1 ships (project-level, not per-PR)

- [ ] OpenID Foundation conformance test suite passed (`test-strategy.md` §4).
- [ ] Known gaps in `threat-model-stride.md` §5 closed or explicitly risk-accepted in writing.
- [ ] External security review completed with zero open critical/high findings (`CLAUDE.md` §6) — this gate blocks real user traffic, not just a "should do eventually" item.
- [ ] JobSeeker completes a real end-to-end integration against a deployed Clavaris instance (`roadmap-and-release-plan.md` §2 exit criterion).
