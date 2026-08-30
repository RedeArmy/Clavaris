package com.clavaris.identity.domain.service;

import java.time.Duration;

/**
 * Pure domain rule shared by the two structurally-identical implementations of ADR-0020 Decision
 * 1's three-way social-login linking decision — {@code
 * authenticatewithsocialprovider.AuthenticateWithSocialProviderService} (tenant tier) and {@code
 * authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderService}
 * (platform tier). Code review finding: those two ~150-line classes duplicate the whole linking
 * algorithm almost line-for-line (no shared base — they operate over distinct aggregate types with
 * no common supertype, {@code Account}/{@code OrganizationId} vs. {@code PlatformAccount}, so a
 * deeper unification is a larger, separately-tracked refactor, not something to rush into
 * security-critical auth code without matching test coverage). Extracting this one constant at
 * least removes the literal duplication and gives both classes one place to look — see each
 * service's own Javadoc for the explicit "if you change the linking decision here, check the other
 * one too" cross-reference.
 */
// PMD.LongVariable: CONFIRMATION_TOKEN_TTL names exactly what it is — same convention every other
// descriptively-named constant in this codebase follows (e.g.
// AuthenticateWithSocialProviderCommand's own emailVerifiedByProvider).
@SuppressWarnings("PMD.LongVariable")
public final class SocialLinkingPolicy {

  // No BR pins an exact figure — same "reasonable default, not yet a tunable" reasoning as
  // RequestEmailVerificationService's own TOKEN_TTL for the structurally identical
  // email-verification flow this confirmation reuses.
  public static final Duration CONFIRMATION_TOKEN_TTL = Duration.ofHours(24);

  private SocialLinkingPolicy() {}
}
