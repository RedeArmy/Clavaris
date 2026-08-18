# Git Workflow — Clavaris

🟡 En revisión

Mirrors JobSeeker's own workflow (`../../JobSeeker/docs/05-engineering/git-workflow.md`) — same author, same discipline, no reason to diverge.

## 1. Branching model

Trunk-based development. `master` is always deployable (this repo's actual default branch — kept as-is, deliberately not renamed to `main`). No long-lived feature branches — short-lived branches per change, merged via PR (even solo, PRs are the review-and-CI gate, not a formality to skip). The one exception is the initial scaffolding commit, which landed directly on `master` as the baseline everything else branches from — branching discipline starts from that point forward, not retroactively.

## 2. Branch naming

`<type>/<short-description>` — `feat/`, `fix/`, `chore/`, `docs/`, `security/` (used specifically for changes closing a `threat-model-stride.md` gap, so security-motivated changes are traceable in history independent of commit message discipline).

## 3. Commit messages

Conventional-commit-style prefix (`feat:`, `fix:`, `docs:`, `chore:`, `security:`), imperative mood, reference the business rule or ADR ID when the commit implements one (`CLAUDE.md` §8) — e.g. `feat: rotate refresh token on use (BR-ID-03)`.

## 4. CI gates before merge

Enforced by `.github/workflows/ci.yml`, triggered on push/PR against `master`, five substantive jobs plus one aggregator:

- `build-and-test` — `mvn verify`: all tests green (`test-strategy.md`), including the ArchUnit hexagonal-dependency check, Spotless formatting (`validate` phase — fails before compilation), and PMD clean-code rules (`verify` phase, `pmd-ruleset.xml`) — `coding-standards.md` §1. None of these are separate CI steps by design; they all run as part of the one `mvn verify` invocation.
- `docker-build` — `app/Dockerfile` actually builds; catches Dockerfile/pom drift as a CI failure instead of a deploy-time surprise.
- `dependency-scan` — OWASP Dependency-Check, fails the build on CVSS ≥ 7 (`security-architecture.md` §8). **`NVD_API_KEY` as a repo secret is required, not just a flakiness reducer** — confirmed live: a cold run with no key and no cached NVD data failed outright (`NullPointerException` during the NVD update, then `NoDataException: No documents exist` once it tried to fall back to local data that didn't exist yet). The job caches the NVD dataset weekly (`~/.dependency-check-data`), but a **separate scheduled workflow, `nvd-cache-refresh.yml`, is what actually populates that cache** (Sundays 02:00 UTC, plus manual `workflow_dispatch`) — confirmed live that leaving this job as the only thing populating its own cache meant the first push against a new/evicted weekly cache paid a full cold-sync cost inline (~380k NVD records, **5 hours** on one real run) before this gate would go green. With the scheduled job pre-warming the same cache key ahead of time, this job should almost always find a warm cache and only need a fast incremental update.
- `doc-consistency` — runs `scripts/check-doc-consistency.sh` (`definition-of-done.md` §1a); a cheap, deliberately non-exhaustive stopgap against the exact class of drift ADR-0010 caused once already.
- `sonarcloud` — full analysis + Quality Gate (§4a below).
- `ci-passed` — depends on all five jobs above (`needs:`), `if: always()`, fails if any of them didn't succeed. Exists purely so GitHub branch protection has **one** status check to require instead of five — requiring each job by name individually breaks silently the moment any one of them is renamed (branch protection keeps waiting for the old name forever, with no obvious reason why a PR won't merge). Runs in parallel with nothing to wait on itself, so it costs no extra wall-clock time.
- No direct commits to `master` — every change goes through a PR, even solo; enforced via GitHub branch protection requiring the `ci-passed` check (a repo setting, not something the workflow file itself can guarantee).

## 4a. SonarCloud Quality Gate — added 2026-08-17, requires one-time manual setup before it's live

The `sonarcloud` job runs `mvn verify` plus the Sonar Maven plugin in one pass, uploading JaCoCo coverage (`pom.xml`/`app/pom.xml` — `jacoco-maven-plugin`) alongside the static analysis. `sonar.qualitygate.wait=true` makes the CI job itself fail if the Quality Gate comes back red — this is what "a commit only reaches `master` once validated" actually means mechanically: a red Quality Gate is a red CI check, and a red CI check is what branch protection (below) refuses to merge.

**Setup status (2026-08-18) — two of three done:**

1. ~~Create the project at sonarcloud.io and set `sonar.projectKey`/`sonar.organization` in `ci.yml`~~ — **done**: project key `RedeArmy_Clavaris`, organization `redearmy`.
2. Generate a token (SonarCloud → My Account → Security) and add it as the `SONAR_TOKEN` repo secret (GitHub → Settings → Secrets and variables → Actions) — **still needed**, confirmed live: the job fails with "Not authorized or project not found" until this exists.
3. Enable Branch Protection on `master` (GitHub → Settings → Branches) requiring every job in `ci.yml` — `sonarcloud` included — to pass before a PR can merge — **still needed**, a repo-admin-only GitHub setting. Until this is set, the `sonarcloud` job existing in the workflow is advisory only; nothing stops a merge if it fails.

Until both remaining items are done, this job fails on every run — expected, not a bug, until setup completes.

## 5. Feature flags

Same philosophy as JobSeeker: trunk-based development relies on flags to merge incomplete work safely rather than long-lived branches. Not yet needed at the current scaffolding stage — revisit once real feature work starts.

## 6. Divergence from JobSeeker's workflow

None currently. If a divergence becomes necessary (e.g. a stricter gate given this system's higher security bar), record it explicitly here rather than letting the two projects silently drift apart.
