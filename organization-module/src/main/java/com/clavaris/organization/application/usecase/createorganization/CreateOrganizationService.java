package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-ORG-06: creates the {@code Organization} row and provisions its initial {@code SigningKey}
 * synchronously, in the same operation — an Organization that exists but cannot yet issue a token
 * is never allowed to be an observable state. {@code @Transactional} covers both this module's own
 * {@code Organization} write and identity-module's {@code SigningKey} write reached through {@link
 * SigningKeyProvisioner}: this is a modular monolith on one database, so both writes share the same
 * connection/transaction manager despite crossing a module boundary — true atomicity, not a
 * best-effort saga. Deliberately does NOT create a {@code RateLimitPolicy} row
 * (BR-ORG-05/BR-ORG-06): a missing row already means "use the system default."
 */
@SuppressWarnings("PMD.LongVariable")
public class CreateOrganizationService implements CreateOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final SigningKeyProvisioner keyProvisioner;
  private final PlatformAccountExistsChecker platformAccountExistsChecker;

  public CreateOrganizationService(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final PlatformAccountExistsChecker platformAccountExistsChecker) {
    this.organizations = organizations;
    this.keyProvisioner = keyProvisioner;
    this.platformAccountExistsChecker = platformAccountExistsChecker;
  }

  @Override
  @Transactional
  public CreateOrganizationResult handle(final CreateOrganizationCommand command) {
    // Security finding (SDE-III review, 2026-08-22): ownerPlatformAccountId used to be trusted
    // as-is — on the dashboard path it always comes from a real authenticated session, but the
    // REST/operator path (CreateOrganizationController) accepts it as caller-supplied JSON with
    // only @NotNull validation. The migration's own comment claims this is "enforced at the
    // application layer only" — this check is that enforcement; before it existed, that claim was
    // false and any UUID, real account or not, produced a real Organization with a real signing
    // key.
    if (!platformAccountExistsChecker.exists(command.ownerPlatformAccountId())) {
      throw new PlatformAccountNotFoundException(command.ownerPlatformAccountId());
    }

    final Organization organization =
        Organization.register(command.name(), command.ownerPlatformAccountId());
    organizations.save(organization);

    final SigningKeyProvisioner.ProvisionedSigningKey signingKey =
        keyProvisioner.provisionFor(organization.id());

    return new CreateOrganizationResult(organization, signingKey);
  }
}
