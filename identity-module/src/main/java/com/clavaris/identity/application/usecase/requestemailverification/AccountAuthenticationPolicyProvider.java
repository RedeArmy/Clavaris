package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — ADR-0024's control-plane read side. Deliberately does not reference
 * organization-module's {@code Organization}/{@code AccountAuthenticationPolicy} types directly,
 * same module-independence rule {@code OrganizationSocialLoginPolicyProvider}/{@code
 * OrganizationEnvironmentChecker} already follow for every other cross-module concern. Implemented
 * in {@code app} by delegating to organization-module's own {@code
 * GetAccountAuthenticationPolicyForOrganizationUseCase} (never empty, defaults included — see that
 * use case's own Javadoc), so this port's own {@code policyFor} never needs to express absence
 * either.
 */
@FunctionalInterface
public interface AccountAuthenticationPolicyProvider {

  AccountAuthenticationPolicySnapshot policyFor(OrganizationId organizationId);
}
