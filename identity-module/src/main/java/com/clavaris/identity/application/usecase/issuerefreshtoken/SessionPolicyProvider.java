package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — deliberately does not reference organization-module's {@code SessionPolicy} type
 * directly, same module-independence rule {@code AccountAuthenticationPolicyProvider} already
 * follows for an identical need. Implemented in {@code app} by delegating to organization-module's
 * own {@code GetSessionPolicyForOrganizationUseCase} (never empty, defaults included), so this
 * port's own {@code policyFor} never needs to express absence either.
 *
 * <p>Parked here, not under {@code rotaterefreshtoken}: {@link IssueRefreshTokenService} and {@code
 * RotateRefreshTokenService} are this port's two real consumers (multi-session handling and
 * max-lifetime/inactivity-timeout enforcement respectively) — placed with the former since a new
 * session is opened before it is ever rotated, same "port lives with its first consumer" precedent
 * {@code AccountAuthenticationPolicyProvider}'s own Javadoc establishes.
 */
@FunctionalInterface
public interface SessionPolicyProvider {

  SessionPolicySnapshot policyFor(OrganizationId organizationId);
}
