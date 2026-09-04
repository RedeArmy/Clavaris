package com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization;

import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import java.util.UUID;

/**
 * Read side of ADR-0024's policy. Depends on {@code AccountAuthenticationPolicyRepository} directly
 * (the same port {@code SetAccountAuthenticationPolicyForOrganizationService} writes through)
 * rather than duplicating a second repository interface for the same table — same "shared port,
 * separate use-case folders" precedent {@code RateLimitPolicyRepository} already establishes for
 * {@code OrganizationCapacityRateLimitingFilter}'s own read.
 */
public class GetAccountAuthenticationPolicyForOrganizationService
    implements GetAccountAuthenticationPolicyForOrganizationUseCase {

  private final AccountAuthenticationPolicyRepository policies;

  public GetAccountAuthenticationPolicyForOrganizationService(
      final AccountAuthenticationPolicyRepository policies) {
    this.policies = policies;
  }

  @Override
  public AccountAuthenticationPolicy handle(final UUID organizationId) {
    return policies
        .findByOrganizationId(organizationId)
        .orElseGet(() -> AccountAuthenticationPolicy.defaults(organizationId));
  }
}
