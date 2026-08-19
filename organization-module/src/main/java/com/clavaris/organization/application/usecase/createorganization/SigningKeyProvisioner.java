package com.clavaris.organization.application.usecase.createorganization;

import java.util.UUID;

/**
 * Outbound port (BR-ORG-06): generates and activates the new Organization's initial signing key,
 * synchronously, in the same operation as its creation. Deliberately does NOT reference {@code
 * SigningKey} or any identity-module type directly — organization-module and identity-module stay
 * mutually independent business modules (CLAUDE.md §7.2's dependency rule applied at the
 * module-graph level, same convention {@code PlatformClientRepository} follows on the
 * client-registry-module side). Implemented in {@code app}, the one module allowed to depend on
 * both, by {@code CreateOrganizationSigningKeyBridge}.
 */
@FunctionalInterface
public interface SigningKeyProvisioner {

  ProvisionedSigningKey provisionFor(UUID organizationId);

  /**
   * Just enough of the provisioned key's metadata to echo back in {@code CreateOrganization}'s own
   * response. PMD's ShortVariable rule flags {@code id} for the same record-style-accessor reason
   * {@code Account} suppresses it elsewhere in this codebase.
   */
  @SuppressWarnings("PMD.ShortVariable")
  record ProvisionedSigningKey(UUID id, String kid, String algorithm) {}
}
