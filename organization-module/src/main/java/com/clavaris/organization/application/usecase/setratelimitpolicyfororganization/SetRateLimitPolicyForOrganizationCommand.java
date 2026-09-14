package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * ADR-0010 §6.2/BR-ORG-05: originally reachable only via the platform-tier management API,
 * "operator-managed only... never a tenant's own token, and never self-service."
 *
 * <p>TD-FUT-002 (self-service tuning, shipped): that exclusivity is corrected, not the hard-cap
 * invariant itself — same "who may call this changed, not what §6.2 requires" distinction {@code
 * RotateSigningKeyForOrganizationCommand}'s own Javadoc already draws for an identical widening.
 * {@code RateLimitPolicy}'s own factory/update methods enforce the exact same hard system-wide cap
 * regardless of which actor triggered the write — there is no narrower self-service sub-ceiling.
 *
 * @param actor either the calling {@code PlatformClient} (TD-SEC-007, the REST admin API path,
 *     resolved by the controller from the request's own {@code Authentication} — deliberately
 *     unscoped to one Organization, since that token represents Clavaris operating on any tenant)
 *     or a {@link AuditActor#platformAccount} actor (the dashboard's own session-authenticated
 *     caller — the Organization's own owning {@code PlatformAccount} tuning its own tenant's
 *     ceiling). The dashboard's own ownership check happens before this command is ever built, not
 *     inside this use case — same split {@code RotateSigningKeyForOrganizationCommand}'s own
 *     Javadoc documents.
 */
public record SetRateLimitPolicyForOrganizationCommand(
    UUID organizationId, int requestsPerMinute, AuditActor actor) {}
