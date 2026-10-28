package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import org.springframework.stereotype.Component;

/**
 * TD-FUT-052: answers identity-module's question from organization-module's authentication policy,
 * the one place allowed to know both (neither module depends on the other). An Organization that
 * never set a policy gets the defaults, where the End-User's own email change is on.
 */
@Component
class SelfServiceEmailChangePolicyBridge implements SelfServiceEmailChangePolicy {

  private final GetAccountAuthenticationPolicyForOrganizationUseCase policies;

  /* package */ SelfServiceEmailChangePolicyBridge(
      final GetAccountAuthenticationPolicyForOrganizationUseCase policies) {
    this.policies = policies;
  }

  @Override
  public boolean isAllowed(final OrganizationId organizationId) {
    return policies.handle(organizationId.value()).selfServiceEmailChangeEnabled();
  }
}
