package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.EnumSet;
import java.util.Set;

/**
 * Outbound port — deliberately does not reference organization-module's {@code Organization} type
 * or repository directly (the module-independence rule applied at the module-graph level, same
 * convention {@code client-registry-module}'s own {@code OrganizationExistsChecker} follows).
 * Implemented in {@code app}, the one module allowed to depend on both, by delegating to
 * organization-module's own {@code OrganizationRepository.findById}.
 *
 * <p>ADR-0020 Decision 3, BR-ID-12, and the threat-model-stride.md §5 fail-closed discipline this
 * codebase already applies elsewhere: {@link AuthenticateWithSocialProviderService} calls {@link
 * #isProviderAllowed} at the actual point of use, not just once at a UI-level gate — a tenant that
 * never enabled a provider (or disabled it after a login page was already rendered) must never be
 * able to complete a social login for it regardless of what the hosted login page's own buttons
 * showed. An unresolvable {@code organizationId} is treated as "not allowed", never as an error to
 * propagate — the caller (BR-ORG-02) already guarantees the id is a real one for the client being
 * authenticated against.
 */
@FunctionalInterface
public interface OrganizationSocialLoginPolicyProvider {

  boolean isProviderAllowed(OrganizationId organizationId, SocialProvider provider);

  /**
   * Code review finding (TD-SEC-032, closed): {@code LoginController}'s own per-render check used
   * to call {@link #isProviderAllowed} once per known {@link SocialProvider} — every implementation
   * this codebase actually has re-resolves the same Organization row on every one of those calls,
   * an avoidable N-lookups-for-one-render pattern that has nothing to do with BR-ID-12's own
   * "re-verify at the point of use" requirement (that requirement is about {@code
   * AuthenticateWithSocialProviderService}'s own single-provider check at the moment a login is
   * actually attempted, never about how many times a page render reads the same set). Default
   * implementation here (correct, in terms of the one real abstract method, but no cheaper) exists
   * so this interface can add this method without breaking its own {@code @FunctionalInterface}
   * contract; the real implementation ({@code OrganizationSocialLoginPolicyProviderBridge})
   * overrides it with a genuine single-lookup path.
   */
  default Set<SocialProvider> allowedProviders(final OrganizationId organizationId) {
    final Set<SocialProvider> allowed = EnumSet.noneOf(SocialProvider.class);
    for (final SocialProvider provider : SocialProvider.values()) {
      if (isProviderAllowed(organizationId, provider)) {
        allowed.add(provider);
      }
    }
    return allowed;
  }
}
