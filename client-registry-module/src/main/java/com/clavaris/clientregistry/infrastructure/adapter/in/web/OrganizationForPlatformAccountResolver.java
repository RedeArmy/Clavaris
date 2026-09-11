package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — deliberately does not reference organization-module's {@code Organization} type
 * or repository directly (module independence, same rule {@code
 * registeroauthclient.OrganizationExistsChecker} already follows in this module). Implemented in
 * {@code app}, the one module allowed to depend on both, by delegating to organization-module's own
 * {@code OrganizationRepository}.
 *
 * <p>Returns just the display name, not the full Organization — this module's own dashboard page
 * only ever needs enough to render its own breadcrumb/header, never the full aggregate. Empty for
 * an unknown organizationId <em>or</em> one owned by a different {@code PlatformAccount} — same
 * "unknown and not-yours look identical, a plain 404" anti-enumeration posture
 * organization-module's own {@code GetOrganizationForPlatformAccountUseCase} documents for itself.
 */
@FunctionalInterface
public interface OrganizationForPlatformAccountResolver {

  @SuppressWarnings("PMD.LongVariable")
  Optional<String> resolveName(UUID organizationId, UUID ownerPlatformAccountId);
}
