#!/usr/bin/env bash
# TD-FUT-039: a plain CREATE INDEX takes a SHARE lock and makes every writer of the table queue
# for the whole build; on a table that already holds rows that is an outage proportional to its
# size. CREATE INDEX CONCURRENTLY does not block writers, and Flyway runs such a migration
# non-transactionally on its own (with spring.flyway.postgresql.transactional-lock=false, which
# application.yml sets) — NonBlockingIndexMigrationTest proves both.
#
# Checks only migrations ADDED on this branch relative to origin/master (released ones are
# immutable anyway). A plain CREATE INDEX is accepted on a table created in that same file, which
# is empty, or with a "-- blocking-index-ok: <reason>" comment on the preceding line.
#
# Exit code 0 = clean (or nothing to compare against). Non-zero = see stderr.

set -uo pipefail
cd "$(dirname "$0")/.."

BASE_REF="origin/master"
if ! git rev-parse --verify "${BASE_REF}" >/dev/null 2>&1; then
    echo "SKIP: '${BASE_REF}' isn't available in this checkout — nothing to compare against."
    exit 0
fi

status=0
while IFS= read -r file; do
    [ -f "$file" ] || continue
    awk -v f="$file" '
        { line = tolower($0) }
        line ~ /create[ \t]+table/ {
            t = line; sub(/.*create[ \t]+table[ \t]+(if[ \t]+not[ \t]+exists[ \t]+)?/, "", t); sub(/[ \t(].*/, "", t); created[t] = 1
        }
        line ~ /create[ \t]+(unique[ \t]+)?index/ && line !~ /concurrently/ {
            o = line; sub(/.*[ \t]on[ \t]+(only[ \t]+)?/, "", o); sub(/[ \t(].*/, "", o)
            if (!(o in created) && prev !~ /blocking-index-ok:/) {
                printf "%s:%d: plain CREATE INDEX on a table this file did not create — use CREATE INDEX CONCURRENTLY, or add \"-- blocking-index-ok: <reason>\" above it\n", f, NR > "/dev/stderr"
                bad = 1
            }
        }
        { prev = line }
        END { exit bad }
    ' "$file" || status=1
done < <(git diff --name-only --diff-filter=A "${BASE_REF}...HEAD" -- '*/src/main/resources/db/migration/*.sql')

exit $status
