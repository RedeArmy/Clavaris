package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0010 §6.2, BR-ORG-05: reachable via the platform-tier management API ({@code
 * AdminApiSecurityConfig}, {@code PlatformScopes.RATE_LIMIT_POLICY_WRITE}, same separation of
 * concerns as {@code CreateOrganizationService}) and, since TD-FUT-002 (self-service tuning,
 * shipped), also via the session-authenticated dashboard ({@code PlatformRateLimitPolicyController}
 * — a second caller, not a second implementation; see {@link
 * SetRateLimitPolicyForOrganizationCommand}'s own Javadoc for the full split).
 *
 * <p>TD-SEC-007: also writes the {@code rate_limit_policy.set} audit event in the same transaction,
 * regardless of which actor triggered the write — every change to a tenant's own capacity ceiling
 * is audited, operator- or self-service-triggered alike.
 */
public class SetRateLimitPolicyForOrganizationService
    implements SetRateLimitPolicyForOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final RateLimitPolicyRepository policies;
  private final int hardSystemWideCap;
  private final AuditEventRecorder auditEvents;

  public SetRateLimitPolicyForOrganizationService(
      final OrganizationRepository organizations,
      final RateLimitPolicyRepository policies,
      final int hardSystemWideCap,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.policies = policies;
    this.hardSystemWideCap = hardSystemWideCap;
    this.auditEvents = auditEvents;
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

    auditEvents.write(
        command.actor(),
        "rate_limit_policy.set",
        "Organization",
        command.organizationId().toString(),
        "requestsPerMinute=" + command.requestsPerMinute());

    return new SetRateLimitPolicyForOrganizationResult(policy);
  }
}
