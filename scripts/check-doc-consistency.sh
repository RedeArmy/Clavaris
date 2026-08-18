#!/usr/bin/env bash
# Documentation consistency check — definition-of-done.md §1a.
#
# What this is: a cheap grep-based stopgap that catches the *specific* class
# of bug that already happened once (ADR-0010 redefined Organization/Account/
# JWKS/rate-limiting; five other docs kept contradicting it until a manual
# sweep caught them). It is NOT a semantic doc linter — it cannot tell you a
# new inconsistency exists, only that a short list of previously-fixed
# regressions haven't crept back in.
#
# When a future ADR renames/redefines something again: add a check for it
# here in the same PR, the same way this file grew out of ADR-0010's cleanup.
#
# Exit code 0 = clean. Non-zero = at least one check failed; see stderr.

set -uo pipefail
cd "$(dirname "$0")/.."

fail=0

check() {
    local description="$1"
    local pattern="$2"
    shift 2
    local -a exclude_files=("$@")

    local grep_args=(-rnE "$pattern" docs/ CLAUDE.md)
    for f in "${exclude_files[@]}"; do
        grep_args+=(--exclude="$f")
    done

    local hits
    hits=$(grep "${grep_args[@]}" 2>/dev/null || true)

    if [[ -n "$hits" ]]; then
        echo "FAIL: $description"
        echo "$hits" | sed 's/^/  /'
        echo
        fail=1
    fi
}

# ADR-0010 renamed the pre-existing "Organization" (workspace) concept to
# "Workspace" — bare Membership/Invitation means the rename didn't propagate,
# UNLESS the line is an intentional historical reference (marked with the
# "pre-ADR-0010" phrase this project consistently uses for that purpose, same
# as ADR-0010's own "Alternatives considered" section, excluded outright).
hits=$(grep -rnE '\b(Membership|Invitation)\b' docs/ CLAUDE.md \
    --exclude="0010-organization-scoped-tenant-isolation.md" 2>/dev/null \
    | grep -v 'pre-ADR-0010' \
    | grep -v 'Rejected alternative' || true)
if [[ -n "$hits" ]]; then
    echo "FAIL: bare 'Membership'/'Invitation' outside Workspace* naming or a 'pre-ADR-0010' historical reference"
    echo "$hits" | sed 's/^/  /'
    echo
    fail=1
fi

# The multi-consumer-identity scenario was resolved by ADR-0010 (Account is
# Organization-scoped). Any *unstruck* claim that it's still open is stale.
if grep -rnE 'not yet resolved' docs/ CLAUDE.md 2>/dev/null | grep -qi 'multi-consumer'; then
    echo "FAIL: multi-consumer-identity scenario referenced as unresolved (ADR-0010 resolved it)"
    fail=1
fi

# ADR-0010 also renumbered the pre-existing BR-ORG-01..03 (ownership/membership
# rules) to BR-WS-01..03, then reused the BR-ORG-* prefix for a DIFFERENT
# meaning (tenant isolation). A line citing "BR-ORG-01" while talking about
# membership/ownership/roles is almost certainly still meaning the OLD rule —
# found live in test-strategy.md after this script already existed, so it
# clearly doesn't catch itself; added here explicitly instead of trusting
# reviewers to remember it a third time.
hits=$(grep -rnE 'BR-ORG-01' docs/ CLAUDE.md 2>/dev/null \
    | grep -E 'membership|ownership|\bOWNER\b' \
    | grep -v 'pre-ADR-0010' \
    | grep -v 'BR-WS-01' || true)
if [[ -n "$hits" ]]; then
    echo "FAIL: 'BR-ORG-01' cited alongside membership/ownership language — likely means the renamed BR-WS-01, not the current tenant-isolation BR-ORG-01"
    echo "$hits" | sed 's/^/  /'
    echo
    fail=1
fi

# The specific stale "related documents" ADR listing found during ADR-0010's
# own cleanup (system-design-document.md). Deliberately a literal string, not
# a general "range stops early" pattern — this repo also has legitimate,
# differently-scoped ADR references (e.g. coding-standards.md §5 lists only
# the currently-*locked* ADRs, which is correct and narrower on purpose).
# Bump this literal when the next ADR after 0010 ships.
check \
    "stale exhaustive-ADR-list reference (should include through 0010)" \
    'ADRs 0001-0009\b' \
    ""

if [[ "$fail" -eq 0 ]]; then
    echo "OK: doc consistency checks passed."
fi

exit "$fail"
