#!/usr/bin/env bash
# Flyway migration immutability check.
#
# What this is: the CI-time enforcement of the exact invariant that broke pre-production on
# 2026-09-16 — V1__enable_pgcrypto.sql's own comment text was edited twice after it had already
# been applied to a real, long-lived database, and Flyway's checksum validation (by design) refused
# to start the application on the next deploy: "Migration checksum mismatch for migration version
# 1". Nothing in this repo's own CI or local dev setup could have caught that before it reached
# pre-production — every test database here is a fresh Testcontainers instance (FlywayMigrationIntegrationTest's
# own POSTGRES container, spun up empty on every run), so a stale-checksum mismatch against an
# already-migrated database structurally cannot surface anywhere except a real, persistent
# environment. This script closes that gap at build time instead: once a migration file has reached
# master, its content (and its filename — a rename changes what Flyway resolves) must never change
# again. Adding a brand-new V*.sql file is always fine; that's how new schema changes ship.
#
# Same "shift the failure left" reasoning FlywayMigrationIntegrationTest#
# everyMigrationVersionFollowsTheTimestampSchemeExceptTheDocumentedV1Exception already applies to a
# different Flyway risk (out-of-order versioning) — that one is a JUnit test because it only needs
# the current file tree; this one is a shell script, not a JUnit test, because it needs git history
# (what did this file look like on master before this PR?), which a Testcontainers-backed
# @SpringBootTest has no natural way to inspect.
#
# Compares this branch's own changes against origin/master (git-workflow.md §4: every change lands
# via a PR against master, so that's always the right "already released" baseline) — a migration
# added and then tweaked again within the *same*, still-unmerged PR is fine; it only becomes
# immutable once it has actually reached master. Needs real git history to do that (actions/
# checkout's fetch-depth: 0, same requirement SonarCloud's own job in ci.yml already has), not the
# default shallow single-commit clone.
#
# Exit code 0 = clean (or nothing to compare against). Non-zero = at least one already-released
# migration was modified, renamed, or deleted; see stderr.

set -uo pipefail
cd "$(dirname "$0")/.."

BASE_REF="origin/master"

if ! git rev-parse --verify "${BASE_REF}" >/dev/null 2>&1; then
    echo "SKIP: '${BASE_REF}' isn't available in this checkout (needs full history — e.g. actions/checkout's fetch-depth: 0) — nothing to compare against."
    exit 0
fi

MERGE_BASE="$(git merge-base HEAD "${BASE_REF}")"
# Every module's own db/migration folder merges into one shared Flyway history at runtime
# (data-model.md §4) — matched wherever it appears, not tied to one specific module's path.
MIGRATION_PATH_PATTERN='/db/migration/V[^/]+\.sql$'

fail=0
while IFS=$'\t' read -r status path1 path2; do
    [[ -z "${status}" ]] && continue

    # A brand-new migration (added since origin/master) is exactly how a real schema change is
    # meant to ship — the only status this check ever allows.
    [[ "${status}" == A* ]] && continue

    # Everything else (M modified, D deleted, R### renamed, C### copied) against a path that is or
    # was a migration file is the exact class of change that broke pre-production — check both
    # sides for a rename/copy, where path1 is the old name and path2 the new one.
    if [[ "${path1}" =~ ${MIGRATION_PATH_PATTERN} ]] || { [[ -n "${path2:-}" ]] && [[ "${path2}" =~ ${MIGRATION_PATH_PATTERN} ]]; }; then
        target="${path1}"
        [[ -n "${path2:-}" ]] && target="${path1} -> ${path2}"
        echo "FAIL: ${target} (${status}) — a Flyway migration that already existed on ${BASE_REF} must never be modified, renamed, or deleted once merged; add a new migration instead"
        fail=1
    fi
done < <(git diff --name-status "${MERGE_BASE}" HEAD)

if [[ "${fail}" -eq 0 ]]; then
    echo "OK: no already-released Flyway migration was modified, renamed, or deleted."
fi

exit "${fail}"
