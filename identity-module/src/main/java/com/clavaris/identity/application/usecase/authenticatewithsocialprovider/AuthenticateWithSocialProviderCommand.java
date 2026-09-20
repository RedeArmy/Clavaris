package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * Input to {@link AuthenticateWithSocialProviderUseCase}. Built by {@code
 * SocialLoginAuthenticationSuccessHandler} (ADR-0020, {@code app} module) after it has already
 * exchanged the provider's authorization code and decoded the returned identity — this use case
 * never talks to Google/GitHub itself (§7.2's dependency rule: no HTTP concepts in {@code
 * application/}).
 *
 * @param providerUserId the provider's own opaque, stable subject identifier — never the email (see
 *     {@code SocialIdentity}'s own Javadoc for why).
 * @param email the account-holder's email as reported by the provider.
 * @param emailVerifiedByProvider ADR-0020 Decision 1's whole linking design assumes this is
 *     trustworthy — {@link AuthenticateWithSocialProviderService} refuses to proceed at all when
 *     this is {@code false} (defense in depth: the adapter should already filter these out, but the
 *     use case does not trust a caller-supplied claim of "verified" without checking it itself).
 * @param firstName ADR-0026: the provider's own given-name claim (Google {@code given_name}, or the
 *     first token of GitHub's {@code name}), {@code null} when the provider offers none — applied
 *     to a brand-new Account only ({@link AuthenticateWithSocialProviderService}'s own
 *     linkBrandNewAccount branch), never on a returning login.
 * @param lastName same provenance/nullability as {@code firstName}.
 * @param username ADR-0026: only ever populated for a provider with a natural, stable, already-
 *     unique handle (GitHub's own {@code login}) — {@code null} for Google, which has no
 *     equivalent; never fabricated from an email local-part or similar guess.
 * @param pictureUrl ADR-0026: the provider's own external picture URL (Google {@code picture},
 *     GitHub {@code avatar_url}), stored on the Account as-is — never re-hosted into Clavaris's own
 *     storage (see that ADR's own Decision for why).
 */
// PMD.LongVariable: emailVerifiedByProvider names exactly what it is — a shortened identifier
// would only make this record harder to read, same convention every other descriptively-named
// field in this codebase follows (e.g. PendingSocialLink's own confirmationTokenHash).
@SuppressWarnings("PMD.LongVariable")
public record AuthenticateWithSocialProviderCommand(
    OrganizationId organizationId,
    SocialProvider provider,
    String providerUserId,
    Email email,
    boolean emailVerifiedByProvider,
    String firstName,
    String lastName,
    String username,
    String pictureUrl) {

  /**
   * Pre-ADR-0026 shape, kept so every existing caller that never touches the four new profile
   * fields (the overwhelming majority — every test in this package) stays unchanged, same "add an
   * overload, don't break existing callers" precedent {@code Account.reconstitute}'s own multi-
   * overload shape already establishes.
   */
  public AuthenticateWithSocialProviderCommand(
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String providerUserId,
      final Email email,
      final boolean emailVerifiedByProvider) {
    this(
        organizationId,
        provider,
        providerUserId,
        email,
        emailVerifiedByProvider,
        null,
        null,
        null,
        null);
  }
}
