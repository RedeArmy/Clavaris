package com.clavaris.organization.application.usecase.deleteorganizationsocialcredential;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0022: reverts an Organization to the shared Clavaris app for the given provider — deleting a
 * row that never existed is a safe, idempotent no-op (same "delete is always safe to repeat"
 * convention {@code RemoveWorkspaceMemberService} already establishes), not an error.
 *
 * <p>{@code @Transactional}: {@code deleteByOrganizationIdAndProvider} is a derived delete query —
 * Spring Data JPA always executes those via a load-then-{@code EntityManager.remove()} strategy (so
 * any {@code @PreRemove}-shaped lifecycle callback still fires), which needs a real transaction the
 * framework's own inherited {@code save()}/{@code findById()} get for free but a custom derived
 * method does not.
 */
public class DeleteOrganizationSocialCredentialService
    implements DeleteOrganizationSocialCredentialUseCase {

  private final OrganizationSocialCredentialRepository credentials;
  private final AuditEventRecorder auditEvents;

  public DeleteOrganizationSocialCredentialService(
      final OrganizationSocialCredentialRepository credentials,
      final AuditEventRecorder auditEvents) {
    this.credentials = credentials;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final DeleteOrganizationSocialCredentialCommand command) {
    credentials.deleteByOrganizationIdAndProvider(command.organizationId(), command.provider());
    auditEvents.write(
        command.actor(),
        "organization.social_credential_deleted",
        "Organization",
        command.organizationId().toString(),
        "provider=" + command.provider());
  }
}
