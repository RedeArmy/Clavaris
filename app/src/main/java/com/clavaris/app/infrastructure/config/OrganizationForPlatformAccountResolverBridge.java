package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.infrastructure.adapter.in.web.OrganizationForPlatformAccountResolver;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements client-registry-module's {@link OrganizationForPlatformAccountResolver} by delegating
 * to organization-module's own {@code OrganizationRepository} — same "app is the one module allowed
 * to depend on both" convention {@code OrganizationExistsCheckerBridge}'s own Javadoc documents,
 * and the exact same ownership-filter logic organization-module's own {@code
 * GetOrganizationForPlatformAccountService} applies for its own dashboard controllers, just
 * projected down to a bare name since that's all this module's own dashboard page ever needs.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class OrganizationForPlatformAccountResolverBridge
    implements OrganizationForPlatformAccountResolver {

  private final OrganizationRepository organizations;

  /* package */ OrganizationForPlatformAccountResolverBridge(
      final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Optional<String> resolveName(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return organizations
        .findById(organizationId)
        .filter(
            organization -> organization.ownerPlatformAccountId().equals(ownerPlatformAccountId))
        .map(Organization::name);
  }
}
