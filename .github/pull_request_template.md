<!--
Even a solo-author PR goes through this — git-workflow.md §1: PRs are the
review-and-CI gate, not a formality to skip. Delete sections that don't
apply to this change; don't leave unchecked boxes that were never relevant.
-->

## What changed and why

<!-- One or two sentences. Reference the business rule or ADR ID if this
     implements one (CLAUDE.md §8), e.g. "Rotates refresh token on use
     (BR-ID-03)." -->

## Checklist (definition-of-done.md)

- [ ] Tests added/updated per `test-strategy.md`
- [ ] `mvn spotless:apply` run — no formatting fixups left for CI to catch
- [ ] No new PMD violation (a genuine false positive gets a targeted
      `@SuppressWarnings` with a comment, never a ruleset-wide exclusion)
- [ ] Comment added/updated per `coding-standards.md` §3 if this encodes a
      business rule or architectural decision
- [ ] No PII, credential, or token value introduced into logs (BR-DATA-01)
- [ ] If this changes a documented decision (ADR, renamed/redefined domain
      concept): `definition-of-done.md` §1a checklist followed, including
      `./scripts/check-doc-consistency.sh`
- [ ] If this touches a migration: `definition-of-done.md` §1b followed
- [ ] If this touches auth/tokens/sessions: `threat-model-stride.md`
      re-checked (`definition-of-done.md` §2)

## How was this verified?

<!-- What you actually ran, not just "CI is green" — e.g. "reproduced the
     original bug locally, confirmed the fix, then ran mvn clean verify." -->
