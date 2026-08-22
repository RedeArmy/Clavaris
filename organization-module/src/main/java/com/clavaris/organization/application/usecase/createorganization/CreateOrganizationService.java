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
public class CreateOrganizationService implements CreateOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final SigningKeyProvisioner keyProvisioner;

  public CreateOrganizationService(
      final OrganizationRepository organizations, final SigningKeyProvisioner keyProvisioner) {
    this.organizations = organizations;
    this.keyProvisioner = keyProvisioner;
  }

  @Override
  @Transactional
  public CreateOrganizationResult handle(final CreateOrganizationCommand command) {
    final Organization organization =
        Organization.register(command.name(), command.ownerPlatformAccountId());
    organizations.save(organization);

    final SigningKeyProvisioner.ProvisionedSigningKey signingKey =
        keyProvisioner.provisionFor(organization.id());

    return new CreateOrganizationResult(organization, signingKey);
  }
}
