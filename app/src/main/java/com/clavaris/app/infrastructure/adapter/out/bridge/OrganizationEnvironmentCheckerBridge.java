package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): adapts
 * organization-module's {@code OrganizationRepository.findById(...).environment()} to
 * client-registry-module's own {@code registeroauthclient.OrganizationEnvironmentChecker} —
 * {@code DEVELOPMENT} vs {@code PRODUCTION} still decides the {@code live_}/{@code test_}
 * credential-id prefix an {@code OAuthClient} is minted with.
 *
 * <p>Correctness finding, 2026-10-08: this bridge used to also implement identity-module's own,
 * identically-shaped {@code requestemailverification.OrganizationEnvironmentChecker} port, which
 * gated real verification/password-reset/sign-in email sends on the same {@code DEVELOPMENT} flag
 * — removed along with that port entirely (see {@code TestEmailAddress}'s own Javadoc for why: a
 * real registrant against any sandboxed Organization, which every Organization defaults to, never
 * got a real verification email at all). Credential-id prefixing stays environment-gated; email
 * delivery no longer is.
 *
 * <p>An organizationId that doesn't resolve to a real Organization defaults to {@code false}
 * (treated as not-Development) — every real caller of this port already holds an organizationId
 * resolved from an existing, FK-equivalent-checked {@code OAuthClient}, so this only matters for a
 * genuinely inconsistent state, and the safe default is to never accidentally mint a
 * {@code live_}-prefixed credential for an unresolvable case.
 */
@Component
class OrganizationEnvironmentCheckerBridge
    implements com.clavaris.clientregistry.application.usecase.registeroauthclient
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
}
