package com.clavaris.organization.application.usecase.deleteorganizationsocialcredential;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.util.ArrayList;
import java.util.List;
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

  private final OrganizationRepository organizations;
  private final OrganizationSocialCredentialRepository credentials;
  private final AuditEventRecorder auditEvents;

  public DeleteOrganizationSocialCredentialService(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.credentials = credentials;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final DeleteOrganizationSocialCredentialCommand command) {
    credentials.deleteByOrganizationIdAndProvider(command.organizationId(), command.provider());
    disableInProduction(command);
    auditEvents.write(
        command.actor(),
        "organization.social_credential_deleted",
        "Organization",
        command.organizationId().toString(),
        "provider=" + command.provider());
  }

  // A PRODUCTION Organization signs in only through its own credentials (ADR-0022, as amended), so
  // once they are gone the provider cannot stay enabled: it would be offered and then fail.
  private void disableInProduction(final DeleteOrganizationSocialCredentialCommand command) {
    final String name = command.provider().name();
    organizations
        .findById(command.organizationId())
        .filter(organization -> organization.environment() == OrganizationEnvironment.PRODUCTION)
        .filter(organization -> organization.allowedSocialProviders().contains(name))
        .ifPresent(organization -> disable(organization, name, command));
  }

  private void disable(
      final Organization organization,
      final String name,
      final DeleteOrganizationSocialCredentialCommand command) {
    final List<String> providers = new ArrayList<>(organization.allowedSocialProviders());
    providers.remove(name);
    organizations.save(organization.withSocialLoginPolicy(!providers.isEmpty(), providers));
    auditEvents.write(
        command.actor(),
        "social_login_policy.set",
        "Organization",
        command.organizationId().toString(),
        "provider=" + command.provider() + " enabled=false");
  }
}
