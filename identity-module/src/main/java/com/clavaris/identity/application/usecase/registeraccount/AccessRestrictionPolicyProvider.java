package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — SDE-III review, 2026-09-19, Clerk "Restrictions" parity. Deliberately does not
 * reference organization-module's {@code AccessRestrictionEntry}/repository types directly, same
 * module-independence rule {@code AccountAuthenticationPolicyProvider}/{@code
 * OrganizationSocialLoginPolicyProvider} already follow. Implemented in {@code app} by delegating
 * to organization-module's own {@code CheckAccessRestrictionForOrganizationUseCase}.
 *
 * <p>An Organization with no restriction entries at all (every Organization today, before any
 * operator ever adds one) allows everything — see {@code AccessRestrictionPolicy}'s own Javadoc.
 */
@FunctionalInterface
public interface AccessRestrictionPolicyProvider {

  boolean isAllowed(OrganizationId organizationId, Email email);
}
