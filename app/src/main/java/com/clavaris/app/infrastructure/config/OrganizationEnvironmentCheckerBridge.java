package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): adapts
 * organization-module's {@code OrganizationRepository.findById(...).environment()} to two
 * structurally identical but deliberately separate outbound ports — client-registry-module's own
 * {@code registeroauthclient.OrganizationEnvironmentChecker} and identity-module's own {@code
 * requestemailverification.OrganizationEnvironmentChecker} — same module-independence reasoning,
 * same "one bridge implementing both is simpler than two near-identical ones" precedent, {@link
 * OrganizationExistsCheckerBridge}'s own Javadoc already establishes for the sibling {@code
 * exists(UUID)} port pair.
 *
 * <p>An organizationId that doesn't resolve to a real Organization defaults to {@code false}
 * (treated as not-Development) — every real caller of this port already holds an organizationId
 * resolved from an existing, FK-equivalent-checked {@code Account}/{@code OAuthClient}, so this
 * only matters for a genuinely inconsistent state, and the safe default is to never accidentally
 * bypass real behaviour (skip a real email send, mint a {@code live_}-prefixed credential) for an
 * unresolvable case.
 */
@Component
class OrganizationEnvironmentCheckerBridge
    implements com.clavaris.clientregistry.application.usecase.registeroauthclient
            .OrganizationEnvironmentChecker,
        com.clavaris.identity.application.usecase.requestemailverification
            .OrganizationEnvironmentChecker {

  private final OrganizationRepository organizations;

  /* package */ OrganizationEnvironmentCheckerBridge(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public boolean isDevelopment(final UUID organizationId) {
    return organizations
        .findById(organizationId)
        .map(organization -> organization.environment() == OrganizationEnvironment.DEVELOPMENT)
        .orElse(false);
  }

  @Override
  public boolean isDevelopment(
      final com.clavaris.identity.domain.model.OrganizationId organizationId) {
    return isDevelopment(organizationId.value());
  }
}
