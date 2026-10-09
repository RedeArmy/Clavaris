package com.clavaris.organization.application.usecase.getsessionpolicyfororganization;

import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.UUID;

/**
 * Read side of the Session Policy feature — depends on {@link SessionPolicyRepository} directly
 * (the same port {@code SetSessionPolicyForOrganizationService} writes through), same "shared port,
 * separate use-case folders" precedent {@code GetRateLimitPolicyForOrganizationService}'s own
 * Javadoc already establishes.
 */
public class GetSessionPolicyForOrganizationService
    implements GetSessionPolicyForOrganizationUseCase {

  private final SessionPolicyRepository policies;

  public GetSessionPolicyForOrganizationService(final SessionPolicyRepository policies) {
    this.policies = policies;
  }

  @Override
  public SessionPolicy handle(final UUID organizationId) {
    return policies
        .findByOrganizationId(organizationId)
        .orElseGet(() -> SessionPolicy.defaults(organizationId));
  }
}
