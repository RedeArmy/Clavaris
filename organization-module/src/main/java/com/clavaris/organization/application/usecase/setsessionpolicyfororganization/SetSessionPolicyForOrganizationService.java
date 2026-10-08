package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import org.springframework.transaction.annotation.Transactional;

/**
 * Also writes the {@code session_policy.set} audit event in the same transaction, regardless of
 * which actor triggered the write — same TD-SEC-007 discipline {@code
 * SetRateLimitPolicyForOrganizationService} already establishes.
 */
public class SetSessionPolicyForOrganizationService
    implements SetSessionPolicyForOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final SessionPolicyRepository policies;
  private final AuditEventRecorder auditEvents;

  public SetSessionPolicyForOrganizationService(
      final OrganizationRepository organizations,
      final SessionPolicyRepository policies,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.policies = policies;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetSessionPolicyForOrganizationResult handle(
      final SetSessionPolicyForOrganizationCommand command) {
    // Same "never trust a caller-supplied id, even from a trusted operator token" discipline as
    // every sibling policy use case's own identical guard.
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    // SessionPolicy's own factory/update methods are what actually enforce each field's range,
    // not this orchestration.
    final SessionPolicy policy =
        policies
            .findByOrganizationId(command.organizationId())
            .map(
                existing ->
                    existing.withPolicy(
                        command.maximumLifetimeMinutes(),
                        command.inactivityTimeoutMinutes(),
                        command.reverificationWindowMinutes(),
                        command.multiSessionHandlingEnabled()))
            .orElseGet(
                () ->
                    SessionPolicy.define(
                        command.organizationId(),
                        command.maximumLifetimeMinutes(),
                        command.inactivityTimeoutMinutes(),
                        command.reverificationWindowMinutes(),
                        command.multiSessionHandlingEnabled()));

    policies.save(policy);

    auditEvents.write(
        command.actor(),
        "session_policy.set",
        "Organization",
        command.organizationId().toString(),
        "maximumLifetimeMinutes="
            + command.maximumLifetimeMinutes()
            + " inactivityTimeoutMinutes="
            + command.inactivityTimeoutMinutes()
            + " reverificationWindowMinutes="
            + command.reverificationWindowMinutes()
            + " multiSessionHandlingEnabled="
            + command.multiSessionHandlingEnabled());

    return new SetSessionPolicyForOrganizationResult(policy);
  }
}
