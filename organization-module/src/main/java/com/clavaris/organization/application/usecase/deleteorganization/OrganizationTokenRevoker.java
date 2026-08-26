package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Outbound port — same "identity-module/organization-module must never depend on Spring
 * Authorization Server" rationale as identity-module's own {@code AccountTokenRevoker} (BR-ID-04),
 * and this is its organization-wide equivalent: every SAS-managed access/ID token belonging to
 * *any* Account or OAuthClient within this Organization, not just one Account's own. Implemented in
 * {@code app} by {@code OrganizationTokenRevokerBridge}, the one module allowed to depend on both
 * organization-module and Spring Authorization Server's own {@code oauth2_authorization} table
 * (TD-SEC-003).
 *
 * <p>Must run before the Organization row itself is deleted — {@code oauth2_authorization} has no
 * FK relationship to {@code accounts}/{@code oauth_clients}/{@code organizations} at all (by
 * design, same reasoning as {@code AccountTokenRevoker}'s own Javadoc), so nothing about deleting
 * this Organization (however far its own real {@code ON DELETE CASCADE} chain reaches) can ever
 * touch this table on its own.
 */
@FunctionalInterface
public interface OrganizationTokenRevoker {

  void revokeAllTokensFor(UUID organizationId);
}
