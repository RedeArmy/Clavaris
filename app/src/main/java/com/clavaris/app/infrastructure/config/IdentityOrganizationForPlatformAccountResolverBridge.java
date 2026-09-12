package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.infrastructure.adapter.in.web.OrganizationForPlatformAccountResolver;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Implements identity-module's {@link OrganizationForPlatformAccountResolver} — same delegation to
 * organization-module's own {@code OrganizationRepository}, and the same ownership-filter logic, as
 * client-registry-module's own identically-shaped {@code
 * OrganizationForPlatformAccountResolverBridge}. A separate class, not the same one implementing
 * both: Java cannot express two {@code resolveName(...)} overloads differing only in parameter
 * types ({@code UUID}/{@code UUID} there, {@link OrganizationId}/{@link PlatformAccountId} here) on
 * one class — same reasoning {@code IdentityCurrentPlatformAccountResolverBridge}'s own Javadoc
 * already documents for an identical situation.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class IdentityOrganizationForPlatformAccountResolverBridge
    implements OrganizationForPlatformAccountResolver {

  private final OrganizationRepository organizations;

  /* package */ IdentityOrganizationForPlatformAccountResolverBridge(
      final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Optional<String> resolveName(
      final OrganizationId organizationId, final PlatformAccountId ownerPlatformAccountId) {
    return organizations
        .findById(organizationId.value())
        .filter(
            organization ->
                organization.ownerPlatformAccountId().equals(ownerPlatformAccountId.value()))
        .map(Organization::name);
  }
}
