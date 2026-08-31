package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * Outbound port — deliberately does not reference organization-module's {@code Organization} type
 * or repository directly (the module-independence rule applied at the module-graph level, same
 * convention {@code client-registry-module}'s own {@code OrganizationExistsChecker} follows).
 * Implemented in {@code app}, the one module allowed to depend on both, by delegating to
 * organization-module's own {@code OrganizationRepository.findById}.
 *
 * <p>ADR-0020 Decision 3, BR-ID-12, and the threat-model-stride.md §5 fail-closed discipline this
 * codebase already applies elsewhere: {@link AuthenticateWithSocialProviderService} calls this at
 * the actual point of use, not just once at a UI-level gate — a tenant that never enabled a
 * provider (or disabled it after a login page was already rendered) must never be able to complete
 * a social login for it regardless of what the hosted login page's own buttons showed. An
 * unresolvable {@code organizationId} is treated as "not allowed", never as an error to propagate —
 * the caller (BR-ORG-02) already guarantees the id is a real one for the client being authenticated
 * against.
 */
@FunctionalInterface
public interface OrganizationSocialLoginPolicyProvider {

  boolean isProviderAllowed(OrganizationId organizationId, SocialProvider provider);
}
