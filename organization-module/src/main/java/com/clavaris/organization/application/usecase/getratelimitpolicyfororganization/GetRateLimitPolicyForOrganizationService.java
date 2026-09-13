package com.clavaris.organization.application.usecase.getratelimitpolicyfororganization;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import java.util.UUID;

/**
 * Read side of ADR-0010 §6.2 — depends on {@link RateLimitPolicyRepository} directly (the same port
 * {@code SetRateLimitPolicyForOrganizationService} writes through) rather than duplicating a second
 * repository interface for the same table, same "shared port, separate use-case folders" precedent
 * {@code GetAccountAuthenticationPolicyForOrganizationService}'s own Javadoc already establishes.
 *
 * <p>Deliberately does NOT reuse {@code RateLimitPolicy#define} to synthesize a value when no row
 * exists — see {@link RateLimitPolicySnapshot}'s own Javadoc for why. {@code
 * systemDefaultRequestsPerMinute} is the exact same {@code
 * clavaris.rate-limit.capacity.default-requests-per-minute} value {@code
 * OrganizationCapacityRateLimitingFilter} (app) already enforces — one config source of truth for
 * what "no override" actually means, not a second copy that could drift.
 *
 * <p>PMD.LongVariable: {@code systemDefaultRequestsPerMinute} matches the config key's own name,
 * not arbitrarily long — same precedent {@code hardSystemWideCap} (the sibling parameter {@code
 * SetRateLimitPolicyForOrganizationService} already carries) establishes.
 */
@SuppressWarnings("PMD.LongVariable")
public class GetRateLimitPolicyForOrganizationService
    implements GetRateLimitPolicyForOrganizationUseCase {

  private final RateLimitPolicyRepository policies;
  private final int systemDefaultRequestsPerMinute;

  public GetRateLimitPolicyForOrganizationService(
      final RateLimitPolicyRepository policies, final int systemDefaultRequestsPerMinute) {
    this.policies = policies;
    this.systemDefaultRequestsPerMinute = systemDefaultRequestsPerMinute;
  }

  @Override
  public RateLimitPolicySnapshot handle(final UUID organizationId) {
    return policies
        .findByOrganizationId(organizationId)
        .map(
            policy ->
                new RateLimitPolicySnapshot(policy.requestsPerMinute(), true, policy.updatedAt()))
        .orElseGet(() -> new RateLimitPolicySnapshot(systemDefaultRequestsPerMinute, false, null));
  }
}
