package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0010 §6.2, BR-ORG-05: v1 is operator-managed only — this use case is reached exclusively via
 * the platform-tier management API ({@code AdminApiSecurityConfig}, {@code
 * PlatformScopes.RATE_LIMIT_POLICY_WRITE}), same separation of concerns as {@code
 * CreateOrganizationService}.
 */
public class SetRateLimitPolicyForOrganizationService
    implements SetRateLimitPolicyForOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final RateLimitPolicyRepository policies;
  private final int hardSystemWideCap;

  public SetRateLimitPolicyForOrganizationService(
      final OrganizationRepository organizations,
      final RateLimitPolicyRepository policies,
      final int hardSystemWideCap) {
    this.organizations = organizations;
    this.policies = policies;
    this.hardSystemWideCap = hardSystemWideCap;
  }

  @Override
  @Transactional
  public SetRateLimitPolicyForOrganizationResult handle(
      final SetRateLimitPolicyForOrganizationCommand command) {
    // Same "never trust a caller-supplied id, even from a trusted operator token" discipline as
    // CreateOrganizationService's own PlatformAccountExistsChecker check (security finding,
    // SDE-III review, 2026-08-22) — a typo'd organizationId here would otherwise silently create
    // an orphaned policy row for an Organization that doesn't exist.
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    // Update in place if a policy already exists (an operator re-tuning an existing ceiling),
    // define a fresh one otherwise — RateLimitPolicy's own factory/update methods are what
    // actually enforce the hard system-wide cap, not this orchestration.
    final RateLimitPolicy policy =
        policies
            .findByOrganizationId(command.organizationId())
            .map(
                existing ->
                    existing.withRequestsPerMinute(command.requestsPerMinute(), hardSystemWideCap))
            .orElseGet(
                () ->
                    RateLimitPolicy.define(
                        command.organizationId(), command.requestsPerMinute(), hardSystemWideCap));

    policies.save(policy);
    return new SetRateLimitPolicyForOrganizationResult(policy);
  }
}
