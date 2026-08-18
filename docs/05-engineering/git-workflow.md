# Git Workflow — Clavaris

🟡 En revisión

Mirrors JobSeeker's own workflow (`../../JobSeeker/docs/05-engineering/git-workflow.md`) — same author, same discipline, no reason to diverge.

## 1. Branching model

Trunk-based development. `main` is always deployable. No long-lived feature branches — short-lived branches per change, merged via PR (even solo, PRs are the review-and-CI gate, not a formality to skip).

## 2. Branch naming

`<type>/<short-description>` — `feat/`, `fix/`, `chore/`, `docs/`, `security/` (used specifically for changes closing a `threat-model-stride.md` gap, so security-motivated changes are traceable in history independent of commit message discipline).

## 3. Commit messages

Conventional-commit-style prefix (`feat:`, `fix:`, `docs:`, `chore:`, `security:`), imperative mood, reference the business rule or ADR ID when the commit implements one (`CLAUDE.md` §8) — e.g. `feat: rotate refresh token on use (BR-ID-03)`.

## 4. CI gates before merge

Enforced by `.github/workflows/ci.yml` (added 2026-08-17), four jobs per PR:

- `build-and-test` — `mvn verify`: all tests green (`test-strategy.md`), including the ArchUnit hexagonal-dependency check once it exists (runs as an ordinary test class, no separate step).
- `docker-build` — `app/Dockerfile` actually builds; catches Dockerfile/pom drift as a CI failure instead of a deploy-time surprise.
- `dependency-scan` — OWASP Dependency-Check, fails the build on CVSS ≥ 7 (`security-architecture.md` §8). Add `NVD_API_KEY` as a repo secret to avoid rate-limit flakiness.
- `doc-consistency` — runs `scripts/check-doc-consistency.sh` (`definition-of-done.md` §1a); a cheap, deliberately non-exhaustive stopgap against the exact class of drift ADR-0010 caused once already.
- No direct commits to `main` — every change goes through a PR, even solo; enforced via GitHub branch protection (a repo setting, not something the workflow file itself can guarantee).

## 5. Feature flags

Same philosophy as JobSeeker: trunk-based development relies on flags to merge incomplete work safely rather than long-lived branches. Not yet needed at the current scaffolding stage — revisit once real feature work starts.

## 6. Divergence from JobSeeker's workflow

None currently. If a divergence becomes necessary (e.g. a stricter gate given this system's higher security bar), record it explicitly here rather than letting the two projects silently drift apart.
