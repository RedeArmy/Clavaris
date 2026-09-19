package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictionPolicyProvider;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization.CheckAccessRestrictionForOrganizationQuery;
import com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization.CheckAccessRestrictionForOrganizationUseCase;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@link CheckAccessRestrictionForOrganizationUseCase} to
 * identity-module's {@link AccessRestrictionPolicyProvider} outbound port — same bridge-lives-in-
 * {@code app} convention as {@code OrganizationSocialLoginPolicyProviderBridge}.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
public class AccessRestrictionPolicyProviderBridge implements AccessRestrictionPolicyProvider {

  private final CheckAccessRestrictionForOrganizationUseCase checkAccessRestriction;

  public AccessRestrictionPolicyProviderBridge(
      final CheckAccessRestrictionForOrganizationUseCase checkAccessRestriction) {
    this.checkAccessRestriction = checkAccessRestriction;
  }

  @Override
  public boolean isAllowed(final OrganizationId organizationId, final Email email) {
    return checkAccessRestriction.handle(
        new CheckAccessRestrictionForOrganizationQuery(organizationId.value(), email.value()));
  }
}
