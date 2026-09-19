package com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.service.AccessRestrictionPolicy;

/**
 * Orchestration for {@link CheckAccessRestrictionForOrganizationUseCase} — the one place that loads
 * an Organization's entries and hands them to the pure {@link AccessRestrictionPolicy} evaluator.
 * Called from {@code app}'s own {@code AccessRestrictionPolicyProviderBridge}, which implements
 * identity-module's {@code AccessRestrictionPolicyProvider} port — same cross-module shape {@code
 * OrganizationSocialLoginPolicyProviderBridge} already establishes.
 */
public class CheckAccessRestrictionForOrganizationService
    implements CheckAccessRestrictionForOrganizationUseCase {

  private final AccessRestrictionEntryRepository entries;

  public CheckAccessRestrictionForOrganizationService(
      final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  @Override
  public boolean handle(final CheckAccessRestrictionForOrganizationQuery query) {
    return AccessRestrictionPolicy.isAllowed(
        entries.findAllByOrganizationId(query.organizationId()), query.email());
  }
}
