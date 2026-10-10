package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository} to identity-module's own {@link
 * OrganizationNameProvider} port — the same module-independence-crossing bridge pattern as {@code
 * ClientHomeUrlResolverBridge}. A blank name counts as no name.
 */
@Component
class OrganizationNameProviderBridge implements OrganizationNameProvider {

  private final OrganizationRepository organizations;

  /* package */ OrganizationNameProviderBridge(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Optional<String> nameFor(final OrganizationId organizationId) {
    return organizations
        .findById(organizationId.value())
        .map(Organization::name)
        .map(String::strip)
        .filter(name -> !name.isEmpty());
  }
}
