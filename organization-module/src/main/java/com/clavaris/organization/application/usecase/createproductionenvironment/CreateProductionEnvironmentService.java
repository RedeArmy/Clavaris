package com.clavaris.organization.application.usecase.createproductionenvironment;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CreateProductionEnvironmentUseCase}. Deliberately mirrors {@code
 * CreateOrganizationService} closely (same {@link SigningKeyProvisioner} call, same "an
 * Organization that exists but cannot yet issue a token is never an observable state" guarantee,
 * same audit discipline) rather than being built as a wrapper around it — the two share their
 * signing-key/audit shape but diverge on validation (a promotion has a source Organization to check
 * invariants against; a fresh creation has none) and on how the new row itself is constructed
 * ({@link Organization#registerProductionEnvironment}, not {@link Organization#register}), so the
 * shared shape is expressed here directly rather than through an awkward wrapping of the other
 * service.
 *
 * <p>{@code @Transactional} covers three writes in one commit: the new {@code PRODUCTION}
 * Organization's own row, the source {@code DEVELOPMENT} Organization's updated {@code
 * linkedEnvironmentOrganizationId} (so the pairing is discoverable from both sides), and
 * identity-module's {@code SigningKey} write reached through {@link SigningKeyProvisioner} — same
 * cross-module-but-one-database atomicity {@code CreateOrganizationService}'s own Javadoc already
 * establishes.
 */
@SuppressWarnings("PMD.LongVariable")
public class CreateProductionEnvironmentService implements CreateProductionEnvironmentUseCase {

  private final OrganizationRepository organizations;
  private final SigningKeyProvisioner keyProvisioner;
  private final AuditEventRecorder auditEvents;

  public CreateProductionEnvironmentService(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.keyProvisioner = keyProvisioner;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public CreateProductionEnvironmentResult handle(
      final CreateProductionEnvironmentCommand command) {
    final Organization developmentOrganization =
        organizations
            .findById(command.developmentOrganizationId())
            .orElseThrow(
                () -> new OrganizationNotFoundException(command.developmentOrganizationId()));

    if (developmentOrganization.environment() != OrganizationEnvironment.DEVELOPMENT) {
      throw new OrganizationNotDevelopmentException(command.developmentOrganizationId());
    }
    if (developmentOrganization.linkedEnvironmentOrganizationId().isPresent()) {
      throw new OrganizationAlreadyHasLinkedEnvironmentException(
          command.developmentOrganizationId());
    }

    // Same owner as the source Organization — the operator/PlatformAccount promoting a sandbox to
    // production is the same one that already owns it, no separate ownership check needed (unlike
    // CreateOrganizationService, which must validate a caller-supplied id it has never seen
    // before).
    final Organization productionOrganization =
        Organization.registerProductionEnvironment(
            command.name(),
            developmentOrganization.ownerPlatformAccountId(),
            developmentOrganization.id());
    organizations.save(productionOrganization);

    organizations.save(
        developmentOrganization.withLinkedEnvironmentOrganizationId(productionOrganization.id()));

    final SigningKeyProvisioner.ProvisionedSigningKey signingKey =
        keyProvisioner.provisionFor(productionOrganization.id());

    auditEvents.write(
        command.actor(),
        "organization.production_environment_created",
        "Organization",
        productionOrganization.id().toString(),
        "developmentOrganizationId=" + developmentOrganization.id());

    return new CreateProductionEnvironmentResult(productionOrganization, signingKey);
  }
}
