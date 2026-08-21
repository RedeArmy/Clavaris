package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import java.util.UUID;

/**
 * Outbound port — deliberately does not reference organization-module's {@code Organization} type
 * or repository directly (the module-independence rule applied at the module-graph level, same
 * convention {@code SigningKeyProvisioner} follows on the organization-module side). Implemented in
 * {@code app}, the one module allowed to depend on both, by delegating to organization-module's own
 * {@code OrganizationRepository.existsById}.
 */
@FunctionalInterface
public interface OrganizationExistsChecker {

  boolean exists(UUID organizationId);
}
