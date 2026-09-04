package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import java.util.UUID;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): outbound
 * port — deliberately does not reference organization-module's {@code Organization}/{@code
 * OrganizationEnvironment} types directly, same module-independence rule {@code
 * OrganizationExistsChecker} already establishes in this same package. Implemented in {@code app},
 * the one module allowed to depend on both, by delegating to organization-module's own {@code
 * OrganizationRepository.findById(...).environment()}.
 *
 * <p>{@link RegisterOAuthClientService} uses this to decide the {@code test_}/{@code live_} prefix
 * on a newly-generated {@code clientId} — same "prove which environment a credential belongs to
 * from the credential itself" mechanic Clerk's own {@code pk_test_}/{@code pk_live_} keys use.
 */
@FunctionalInterface
public interface OrganizationEnvironmentChecker {

  boolean isDevelopment(UUID organizationId);
}
