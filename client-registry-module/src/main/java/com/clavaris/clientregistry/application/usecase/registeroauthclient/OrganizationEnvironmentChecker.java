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
 * <p><b>SDE-III correction, 2026-09-24:</b> {@link RegisterOAuthClientService} no longer uses this
 * port — {@code OAuthClient.clientId} is now a fixed {@code client_} prefix regardless of
 * environment (that class's own Javadoc has the full reasoning). The one remaining consumer is
 * {@code createorganizationclient.CreateOrganizationClientService}, which still uses this to decide
 * the {@code sk_test_}/{@code sk_live_} prefix on a newly-generated {@code OrganizationClient}
 * secret key — env-on-credential-pair is the correct half of Stripe's own convention, unlike a
 * plain resource id.
 */
@FunctionalInterface
public interface OrganizationEnvironmentChecker {

  boolean isDevelopment(UUID organizationId);
}
