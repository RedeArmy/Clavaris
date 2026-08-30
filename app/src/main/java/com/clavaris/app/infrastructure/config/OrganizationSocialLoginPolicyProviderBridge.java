package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository.findById} to identity-module's {@link
 * OrganizationSocialLoginPolicyProvider} outbound port — the bridge lives in {@code app}, not
 * either business module, same convention as {@code OrganizationExistsCheckerBridge}: it needs both
 * at once and {@code app} is the one module allowed to (the module-graph's dependency rule).
 *
 * <p>Code review finding: a social login attempt calls {@link #isProviderAllowed} up to three times
 * (login-button render, pre-redirect check, post-callback re-check), each a full, uncached {@code
 * Organization} row read. Deliberately left uncached, not missed: the third call — inside {@code
 * AuthenticateWithSocialProviderService} itself — exists specifically per ADR-0020 Decision
 * 3/BR-ID-12 to catch an operator disabling a provider mid-flow, "re-verified here, at the point of
 * actual use, not trusted from an earlier UI-level gate" (that service's own comment); caching this
 * read would reintroduce exactly the staleness window BR-ID-12 exists to close. Same "no premature
 * caching" precedent {@code RateLimitPolicyRepository}/TD-FUT-012 already accepts elsewhere in this
 * codebase — three extra Postgres reads on a login attempt (not a hot path) is a deliberate,
 * accepted cost, not an oversight.
 */
@Component
class OrganizationSocialLoginPolicyProviderBridge implements OrganizationSocialLoginPolicyProvider {

  private final OrganizationRepository organizations;

  /* package */ OrganizationSocialLoginPolicyProviderBridge(
      final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public boolean isProviderAllowed(
      final OrganizationId organizationId, final SocialProvider provider) {
    final Optional<Organization> organization = organizations.findById(organizationId.value());
    // An unresolvable organizationId is treated as "not allowed", never propagated as an error —
    // see this port's own Javadoc for why.
    return organization
        .map(
            found ->
                found.socialLoginEnabled()
                    && found.allowedSocialProviders().contains(provider.name()))
        .orElse(false);
  }
}
