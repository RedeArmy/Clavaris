#!/usr/bin/env bash
# TD-TEST-015: Flyway's default advisory lock keeps a transaction open for the whole migration, and a
# CREATE INDEX CONCURRENTLY migration waits for every older transaction, its own included, so the
# first such migration deadlocks any context that has not switched the lock to session level.
# application.yml does that for the application (spring.flyway.postgresql.transactional-lock: false),
# but a module's slice tests build their own Spring context and never read it, and a test that calls
# Flyway.configure() itself reads nothing. On 2026-11-04 that ran both CI jobs to their time limit.
#
# Checks, with no database and no build:
#   1. every module with tests other than app sets the lock in its own test application.properties;
#   2. app's application.yml sets it (its tests inherit that file);
#   3. every test that builds a Flyway by hand over the real migrations (classpath:db/migration) sets
#      flyway.postgresql.transactional.lock in the same file.
#
# Exit code 0 = clean. Non-zero = see stderr.

set -uo pipefail
cd "$(dirname "$0")/.."

status=0
complain() {
    echo "$1" >&2
    status=1
}

for pom in */pom.xml; do
    module="${pom%/pom.xml}"
    [ -d "${module}/src/test/java" ] || continue
    [ "${module}" = "app" ] && continue
    props="${module}/src/test/resources/application.properties"
    if ! grep -qE '^[[:space:]]*spring\.flyway\.postgresql\.transactional-lock[[:space:]]*=[[:space:]]*false[[:space:]]*$' "${props}" 2>/dev/null; then
        complain "${props}: must contain spring.flyway.postgresql.transactional-lock=false (its tests build their own context; see this script's header)"
    fi
done

if ! grep -qE '^[[:space:]]*transactional-lock:[[:space:]]*false' app/src/main/resources/application.yml; then
    complain "app/src/main/resources/application.yml: spring.flyway.postgresql.transactional-lock must be false (app's tests and the running application read it)"
fi

while IFS= read -r file; do
    if grep -q 'Flyway\.configure()' "${file}" && grep -q 'classpath:db/migration"' "${file}"; then
        if ! grep -q 'flyway\.postgresql\.transactional\.lock' "${file}"; then
            complain "${file}: builds a Flyway over classpath:db/migration by hand without flyway.postgresql.transactional.lock=false"
        fi
    fi
done < <(find . -path '*/src/test/*' -name '*.java' -not -path '*/target/*')

if [ "${status}" -eq 0 ]; then
    echo "OK: every Flyway that runs the real migrations in a test uses the session-level lock."
fi
exit "${status}"
