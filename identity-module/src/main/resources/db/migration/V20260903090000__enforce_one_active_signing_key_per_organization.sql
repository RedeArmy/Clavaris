-- SDE-III review, 2026-09-03 — real bug found and closed: "one active signing key per
-- Organization" was only a plain (non-unique) partial index, never actually enforced. Two
-- concurrent rotate/purge calls for the same Organization (a double-submit, or a legitimate
-- rotate racing an emergency purge) could each read the same currently-active key, each retire
-- it, and each independently activate a new one — leaving two rows with retired_at IS NULL for
-- one Organization, which key new tokens sign under becoming nondeterministic. Undermines the
-- "signing keys rotate with overlap" invariant CLAUDE.md §6 calls non-negotiable.
--
-- The actual serialization now happens via SELECT ... FOR UPDATE in
-- ActivateSigningKeyForOrganizationService (see its own Javadoc) — this UNIQUE partial index is
-- the DB-level backstop, not the primary defense, same "constraint as belt-and-suspenders
-- backstop, not sole enforcement" precedent V20260830110000's own deferred trigger already
-- establishes for BR-ID-02.
DROP INDEX ix_signing_keys_organization_id_active;

CREATE UNIQUE INDEX ux_signing_keys_organization_id_active
    ON signing_keys (organization_id) WHERE retired_at IS NULL;
