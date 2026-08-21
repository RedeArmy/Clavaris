package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationExistsChecker;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository.existsById} to
 * client-registry-module's {@link OrganizationExistsChecker} outbound port — the bridge lives in
 * {@code app}, not either business module, same convention as {@code
 * CreateOrganizationSigningKeyBridge}: it needs both at once and {@code app} is the one module
 * allowed to (the module-graph's dependency rule).
 *
 * <p>Landed in the same PR as {@code RegisterOAuthClientUseCase}, not deferred to a later one —
 * confirmed live once already (that earlier fix's own commit message has the full story): without
 * this bean present, {@code app}'s own pre-existing full-context tests fail to start the moment
 * client-registry-module's {@code RegisterOAuthClientUseCase} bean (which requires this port) is on
 * the classpath, not just anything new.
 */
@Component
class OrganizationExistsCheckerBridge implements OrganizationExistsChecker {

  private final OrganizationRepository organizations;

  /* package */ OrganizationExistsCheckerBridge(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public boolean exists(final UUID organizationId) {
    return organizations.existsById(organizationId);
  }
}
