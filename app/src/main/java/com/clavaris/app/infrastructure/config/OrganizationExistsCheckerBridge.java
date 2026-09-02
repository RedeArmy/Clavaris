package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository.existsById} to two structurally
 * identical but deliberately separate outbound ports — client-registry-module's own {@code
 * registeroauthclient.OrganizationExistsChecker} and webhook-module's own {@code
 * registerwebhookendpoint.OrganizationExistsChecker} (module independence: neither business module
 * may depend on the other's port type, even though both ports declare the exact same {@code boolean
 * exists(UUID)} method — same module-graph reasoning each port's own Javadoc already documents).
 * One bridge class implementing both is simpler than two near-identical ones; the bridge lives in
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
class OrganizationExistsCheckerBridge
    implements com.clavaris.clientregistry.application.usecase.registeroauthclient
            .OrganizationExistsChecker,
        com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationExistsChecker {

  private final OrganizationRepository organizations;

  /* package */ OrganizationExistsCheckerBridge(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public boolean exists(final UUID organizationId) {
    return organizations.existsById(organizationId);
  }
}
