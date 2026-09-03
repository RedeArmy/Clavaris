package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code OrganizationRepository.findById} to identity-module's {@link
 * OrganizationSocialLoginPolicyProvider} outbound port — the bridge lives in {@code app}, not
 * either business module, same convention as {@code OrganizationExistsCheckerBridge}: it needs both
 * at once and {@code app} is the one module allowed to (the module-graph's dependency rule).
 *
 * <p>Code review finding (TD-SEC-032), two-part resolution: a social login attempt calls this
 * bridge up to three times (login-button render, pre-redirect check, post-callback re-check).
 * {@link #allowedProviders} closes the first, genuinely wasteful part — {@code LoginController}'s
 * own render used to call {@link #isProviderAllowed} once per known {@link SocialProvider} (N
 * lookups of the identical row for one page render); this override does the same job with exactly
 * one {@link OrganizationRepository#findById} call, checked against the resulting set in memory.
 * The remaining two {@link #isProviderAllowed} calls (pre-redirect check, and {@code
 * AuthenticateWithSocialProviderService}'s own post-callback re-check) are deliberately left
 * uncached, not missed: the second of those exists specifically per ADR-0020 Decision 3/BR-ID-12 to
 * catch an operator disabling a provider mid-flow, "re-verified here, at the point of actual use,
 * not trusted from an earlier UI-level gate" (that service's own comment) — caching it would
 * reintroduce exactly the staleness window BR-ID-12 exists to close — unlike {@link
 * CachingRateLimitPolicyRepository}/TD-FUT-012 (closed 2026-09-02), whose own read has no such
 * per-request-freshness requirement to protect. Two reads on a login attempt (not a hot path) is a
 * deliberate, accepted cost here, not an oversight.
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

  @Override
  public Set<SocialProvider> allowedProviders(final OrganizationId organizationId) {
    final Optional<Organization> organization = organizations.findById(organizationId.value());
    final Set<SocialProvider> allowed = EnumSet.noneOf(SocialProvider.class);
    // PMD.OnlyOneReturn: guard folded into the loop's own precondition, rather than an early
    // return, so this method keeps a single exit point — an unresolvable/disabled organization
    // simply skips the loop and returns the still-empty set.
    if (organization.isPresent() && organization.get().socialLoginEnabled()) {
      // SetSocialLoginPolicyForOrganizationService's own KNOWN_PROVIDERS allowlist already
      // guarantees every persisted name here matches a real SocialProvider constant — valueOf(),
      // not a defensive filter, same trust level isProviderAllowed()'s own
      // .contains(provider.name())
      // check above already places in this same list.
      for (final String providerName : organization.get().allowedSocialProviders()) {
        allowed.add(SocialProvider.valueOf(providerName));
      }
    }
    return allowed;
  }
}
