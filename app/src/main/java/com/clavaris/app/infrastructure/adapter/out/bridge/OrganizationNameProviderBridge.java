package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository} to identity-module's own {@link
 * OrganizationNameProvider} port — the same module-independence-crossing bridge pattern as {@code
 * ClientHomeUrlResolverBridge}. A blank name counts as no name.
 *
 * <p>The name an Organization chose for its consuming application (set from its dashboard card)
 * wins over the Organization's own name, so the emails and the passkey prompt say what the sign-in
 * page says.
 */
@Component
class OrganizationNameProviderBridge implements OrganizationNameProvider {

  private final OrganizationRepository organizations;
  private final GetOrganizationProfilesUseCase getProfiles;

  /* package */ OrganizationNameProviderBridge(
      final OrganizationRepository organizations,
      final GetOrganizationProfilesUseCase getProfiles) {
    this.organizations = organizations;
    this.getProfiles = getProfiles;
  }

  @Override
  public Optional<String> nameFor(final OrganizationId organizationId) {
    final UUID organization = organizationId.value();
    return applicationName(organization).or(() -> organizationName(organization));
  }

  private Optional<String> applicationName(final UUID organization) {
    return Optional.ofNullable(getProfiles.handle(List.of(organization)).get(organization))
        .flatMap(OrganizationProfile::applicationName)
        .map(String::strip)
        .filter(name -> !name.isEmpty());
  }

  private Optional<String> organizationName(final UUID organization) {
    return organizations
        .findById(organization)
        .map(Organization::name)
        .map(String::strip)
        .filter(name -> !name.isEmpty());
  }
}
