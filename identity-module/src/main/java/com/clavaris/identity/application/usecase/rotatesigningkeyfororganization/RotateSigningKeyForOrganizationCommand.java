package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * ADR-0010 §5.2: v1 rotation is manually-triggered, audited, and real overlap-preserving —
 * originally reachable only via the platform-tier management API, "operator-only, never
 * self-service."
 *
 * <p>SDE-III review, 2026-09-11: that exclusivity is corrected, not the rotation mechanism or
 * overlap guarantee itself — same "who may call this changed, not what §5.2 requires" distinction
 * ADR-0010's own "Addendum — self-service Organization creation now exists" already draws for an
 * identical situation (operator-only creation widened to add a session-authenticated {@code
 * PlatformAccount} path via ADR-0012, without touching cross-tenant isolation or any other §1–§6
 * guarantee). Rotation is safe to widen the same way — it never breaks a still-valid,
 * already-issued token (that's the entire point of the overlap window) — unlike {@code
 * PurgeSigningKeyForOrganizationCommand}'s own zero-overlap emergency purge, which stays
 * operator-only: deliberately NOT widened alongside this command, since an accidental self-service
 * purge would break every one of an Organization's own currently-valid tokens immediately, not just
 * rotate them with grace.
 *
 * @param actor either the calling {@code PlatformClient} (TD-SEC-007, the REST admin API path,
 *     resolved by the controller from the request's own {@code Authentication}) or a {@link
 *     AuditActor#platformAccount} actor (the dashboard's own session-authenticated caller — the
 *     Organization's own owning {@code PlatformAccount} rotating its own tenant's signing key). The
 *     dashboard's own ownership check happens before this command is ever built, not inside this
 *     use case.
 */
public record RotateSigningKeyForOrganizationCommand(
    OrganizationId organizationId, AuditActor actor) {}
